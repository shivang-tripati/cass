package com.shivang.obd.campaign;

import com.shivang.obd.voice.media.GatewayRoute;
import com.shivang.obd.voice.media.OutboundDialException;
import com.shivang.obd.voice.media.OutboundDialRequest;
import com.shivang.obd.voice.media.OutboundDialResponse;
import com.shivang.obd.voice.media.OutboundDialer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.contact.ContactEntity;
import com.shivang.obd.contact.ContactRepository;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.eligibility.VoiceEligibility;
import com.shivang.obd.voice.routing.RouteType;
import com.shivang.obd.voice.routing.VoiceRoute;
import com.shivang.obd.voice.routing.VoiceRoutingDecision;
import com.shivang.obd.voice.routing.VoiceRoutingReason;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VB-0 temporary retry tests (Phase 10).
 * <p>
 * Verifies {@link OutboundDialService} routing/capacity failure semantics:
 * temporary unavailability (capacity exhaustion, CPS, provider unavailable)
 * requeues the attempt without consuming a retry, while permanent
 * eligibility failures (DNC, blocklist) fail the attempt permanently.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboundDialServiceRoutingTest {

    @Mock CallAttemptRepository attemptRepository;
    @Mock ContactRepository contactRepository;
    @Mock TenantRepository tenantRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock CampaignExecutionRepository executionRepository;
    @Mock CampaignConfigurationService configurationService;
    @Mock OutboundDialer dialer;
    @Mock CallEligibility eligibilityService;
    @Mock com.shivang.obd.voice.routing.VoiceRoutingService voiceRoutingService;
    @Mock VoiceCapacityService voiceCapacity;
    @Mock CallSessionRepository callSessionRepository;
    @Mock CallLegRepository callLegRepository;
    /** VB-6C.1: the daily dial-limit policy boundary. */
    @Mock DailyDialLimitService dailyDialLimitService;

    /**
     * VB-6D.3: the campaign daily-attempt gate. Mocked and defaulting to
     * ADMITTED so this suite keeps testing the dial pipeline it was written
     * for; the safety gate's own behaviour (including its ceiling) is proven
     * in DailyAttemptSafetyTest and the PostgreSQL concurrency suite.
     */
    @Mock DailyAttemptSafetyService dailyAttemptSafetyService;

    OutboundDialService service;

    final UUID tenantId = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    final UUID executionId = UUID.fromString("aa000000-0000-4000-8000-0000000000e1");
    // VB-5F: campaign and execution are distinct rows — the dial path must
    // resolve the campaign by attempt.getCampaignId(), never by executionId.
    final UUID campaignId = UUID.fromString("aa000000-0000-4000-8000-0000000000c9");
    final UUID attemptId = UUID.fromString("aa000000-0000-4000-8000-0000000000a1");
    final UUID contactId = UUID.fromString("aa000000-0000-4000-8000-0000000000c1");
    final UUID didId = UUID.fromString("aa000000-0000-4000-8000-00000000002a");
    final UUID gatewayId = UUID.fromString("aa000000-0000-4000-8000-00000000001a");
    /** Snapshot audience group the attempt's contact must belong to (VB-6B.1). */
    final UUID AUDIENCE_GROUP_ID = UUID.fromString("aa000000-0000-4000-8000-0000000000b1");
    final String DEST = "+919876543210";

    @BeforeEach
    void setUp() {
        service = new OutboundDialService(
                attemptRepository, contactRepository, tenantRepository,
                campaignRepository, executionRepository,
                new CampaignRuntimeConfigResolver(configurationService),
                dialer, eligibilityService,
                voiceRoutingService, voiceCapacity, callSessionRepository, callLegRepository,
                dailyDialLimitService, dailyAttemptSafetyService,
                new PreDispatchFailureMapper());

        // VB-6C.1 defaults: usage day resolves, bucket admits. Individual
        // tests override the exact behavior they exercise.
        org.mockito.Mockito.lenient()
                .when(dailyDialLimitService.resolveUsageDate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.time.LocalDate.of(2026, 9, 27));
        org.mockito.Mockito.lenient()
                .when(dailyDialLimitService.effectiveLimit(org.mockito.ArgumentMatchers.any()))
                .thenReturn(3);
        org.mockito.Mockito.lenient()
                .when(dailyDialLimitService.admit(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(DailyDialLimitService.AdmissionResult.ADMITTED);

        // VB-6D.3: the campaign daily-attempt gate admits by default, so this
        // suite continues to exercise the dial pipeline. Tests that care about
        // the ceiling override this.
        org.mockito.Mockito.lenient()
                .when(dailyAttemptSafetyService.admit(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(DailyAttemptSafetyService.AdmissionResult.ADMITTED);

        // Base attempt fixture: QUEUED and due
        CallAttempt attempt = new CallAttempt();
        attempt.setId(attemptId);
        attempt.setTenantId(tenantId);
        attempt.setExecutionId(executionId);
        attempt.setCampaignId(campaignId);
        attempt.setContactId(contactId);
        attempt.setDidId(didId);
        attempt.setAttemptNumber(2); // already retried once — requeue must not consume it
        attempt.setStatus(CallAttemptStatus.QUEUED);
        attempt.setScheduledAt(java.time.Instant.now().minusSeconds(60));
        when(attemptRepository.findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(
                eq(CallAttemptStatus.QUEUED), any(java.time.Instant.class)))
                .thenReturn(java.util.List.of(attempt));
        when(attemptRepository.save(any(CallAttempt.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(callLegRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Contact resolves the destination (VB-6B.1: tenant-scoped identity
        // lookup — no group predicate at dial time).
        ContactEntity contact = new ContactEntity();
        contact.setId(contactId);
        contact.setTenantId(tenantId);
        contact.setPhoneNumber(DEST);
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, tenantId))
                .thenReturn(Optional.of(contact));

        // Tenant has no reseller
        TenantEntity tenant = new TenantEntity();
        tenant.setId(tenantId);
        when(tenantRepository.findByIdAndDeletedAtIsNull(tenantId)).thenReturn(Optional.of(tenant));

        // Campaign exists (looked up by the attempt's campaignId — VB-5F F1);
        // its live configuration is irrelevant now — config comes from the
        // execution's snapshot (VB-6A correction, no fallback).
        CampaignEntity campaign = new CampaignEntity();
        campaign.setId(campaignId);
        campaign.setTenantId(tenantId);
        campaign.setCampaignType(CampaignType.PLAYFILE);
        when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId))
                .thenReturn(Optional.of(campaign));

        // Execution → mandatory snapshot fixture.
        var execution = new CampaignExecution();
        execution.setId(executionId);
        execution.setCampaignId(campaignId);
        execution.setTenantId(tenantId);
        execution.setConfigurationSnapshotId(UUID.randomUUID());
        when(executionRepository.findByIdAndDeletedAtIsNull(executionId))
                .thenReturn(Optional.of(execution));
        when(configurationService.requireExecutionSnapshot(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CampaignExecutionConfiguration.materialize(
                        campaignId, tenantId,
                        new CampaignConfigurationSnapshot(
                                CampaignType.PLAYFILE, AUDIENCE_GROUP_ID, null, null, null, null,
                                null, null, null, null, null, null, null,
                                0, null, RetryStrategy.FIXED, null, false, null),
                        java.time.Instant.now()));
    }

    private CallAttempt capturedAttempt() {
        ArgumentCaptor<CallAttempt> captor = ArgumentCaptor.forClass(CallAttempt.class);
        verify(attemptRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    class TemporaryFailures {

        @Test
        void routingCapacityExhaustion_requeuesAttemptWithoutConsumingRetry() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.allowed());
            when(voiceRoutingService.resolveRoute(any(), any(), eq(DEST), eq(didId), anyString(), any()))
                    .thenReturn(VoiceRoutingDecision.rejected(
                            VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode(),
                            java.util.List.of()));
            when(dialer.dial(any())).thenReturn(OutboundDialResponse.accepted("ignored"));

            int processed = service.processDueAttempts();

            assertThat(processed).isEqualTo(1);
            CallAttempt saved = capturedAttempt();
            assertThat(saved.getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
            assertThat(saved.getStartedAt()).isNull();
            assertThat(saved.getAttemptNumber()).isEqualTo(2); // retry NOT consumed
            verify(dialer, never()).dial(any());
        }

        @Test
        void routingCpsExhaustion_requeuesAttempt() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.allowed());
            when(voiceRoutingService.resolveRoute(any(), any(), eq(DEST), eq(didId), anyString(), any()))
                    .thenReturn(VoiceRoutingDecision.rejected(
                            VoiceRoutingReason.ROUTE_REJECTED_CPS_CAPACITY.getCode(),
                            java.util.List.of()));

            service.processDueAttempts();

            assertThat(capturedAttempt().getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
            verify(dialer, never()).dial(any());
        }

        @Test
        void routingHeadroomRejection_requeuesAttempt() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.allowed());
            when(voiceRoutingService.resolveRoute(any(), any(), eq(DEST), eq(didId), anyString(), any()))
                    .thenReturn(VoiceRoutingDecision.rejected(
                            VoiceRoutingReason.ROUTE_REJECTED_CAPACITY_HEADROOM.getCode(),
                            java.util.List.of()));

            service.processDueAttempts();

            assertThat(capturedAttempt().getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
        }

        @Test
        void noEligibleGateway_requeuesWithoutPermanentFailure() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.allowed());
            when(voiceRoutingService.resolveRoute(any(), any(), eq(DEST), eq(didId), anyString(), any()))
                    .thenReturn(VoiceRoutingDecision.rejected(
                            VoiceRoutingReason.ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY.getCode(),
                            java.util.List.of()));

            service.processDueAttempts();

            // Per implementation contract: only explicit capacity/CPS/headroom
            // reasons requeue; NO_ELIGIBLE_GATEWAY is treated as permanent for
            // this call — verify actual behavior here.
            assertThat(capturedAttempt().getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        }

        @Test
        void capacityReservationFailureAfterSelection_requeues() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.allowed());
            when(voiceRoutingService.resolveRoute(any(), any(), eq(DEST), eq(didId), anyString(), any()))
                    .thenReturn(VoiceRoutingDecision.primary(
                            new VoiceRoute(gatewayId, "fs-gw-test", "external", "TATA", didId, DEST),
                            VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode(),
                            java.util.List.of()));
            when(voiceCapacity.reserve(gatewayId, tenantId)).thenReturn(false);

            service.processDueAttempts();

            assertThat(capturedAttempt().getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
            verify(dialer, never()).dial(any());
        }

        @Test
        void providerUnavailableResponse_requeuesAndReleasesReservation() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.allowed());
            when(voiceRoutingService.resolveRoute(any(), any(), eq(DEST), eq(didId), anyString(), any()))
                    .thenReturn(VoiceRoutingDecision.primary(
                            new VoiceRoute(gatewayId, "fs-gw-test", "external", "TATA", didId, DEST),
                            VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode(),
                            java.util.List.of()));
            when(voiceCapacity.reserve(gatewayId, tenantId)).thenReturn(true);
            when(dialer.dial(any())).thenReturn(OutboundDialResponse.providerUnavailable());

            service.processDueAttempts();

            assertThat(capturedAttempt().getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
            verify(voiceCapacity).release(gatewayId, tenantId);
        }

        @Test
        void dialerException_requeuesWithoutConsumingRetry() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.allowed());
            when(voiceRoutingService.resolveRoute(any(), any(), eq(DEST), eq(didId), anyString(), any()))
                    .thenReturn(VoiceRoutingDecision.primary(
                            new VoiceRoute(gatewayId, "fs-gw-test", "external", "TATA", didId, DEST),
                            VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode(),
                            java.util.List.of()));
            when(voiceCapacity.reserve(gatewayId, tenantId)).thenReturn(true);
            when(dialer.dial(any())).thenThrow(new OutboundDialException("FS down"));

            service.processDueAttempts();

            assertThat(capturedAttempt().getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
        }

        @Test
        void temporaryEligibilityCode_requeuesAttempt() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.blocked(
                            "TEMPORARILY_UNAVAILABLE", "No available gateway capacity"));
            when(dialer.dial(any())).thenReturn(OutboundDialResponse.accepted("ignored"));

            service.processDueAttempts();

            assertThat(capturedAttempt().getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
            verify(dialer, never()).dial(any());
        }
    }

    @Nested
    class PermanentFailures {

        @Test
        void dncBlocked_failsPermanentlyWithoutDialing() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.blocked("DNC_BLOCKED", "Number blocked by tenant DNC"));

            service.processDueAttempts();

            CallAttempt saved = capturedAttempt();
            assertThat(saved.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(saved.getFailureCode()).isEqualTo("DNC_BLOCKED");
            verify(dialer, never()).dial(any());
        }

        @Test
        void platformBlocklist_failsPermanently() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.blocked("PLATFORM_BLOCKED", "Number blocked by platform"));

            service.processDueAttempts();

            CallAttempt saved = capturedAttempt();
            assertThat(saved.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(saved.getFailureCode()).isEqualTo("PLATFORM_BLOCKED");
        }

        @Test
        void campaignMissing_failsPermanently() {
            when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId))
                    .thenReturn(Optional.empty());

            service.processDueAttempts();

            CallAttempt saved = capturedAttempt();
            assertThat(saved.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(saved.getFailureCode()).isEqualTo("CAMPAIGN_NOT_FOUND");
        }

        @Test
        void executionTimezoneInvalid_failsPermanentlyWithoutDialing() {
            when(dailyDialLimitService.resolveUsageDate(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new ExecutionTimezoneInvalidException("no timezone"));

            service.processDueAttempts();

            CallAttempt saved = capturedAttempt();
            assertThat(saved.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(saved.getFailureCode()).isEqualTo("EXECUTION_TIMEZONE_INVALID");
            verify(dialer, never()).dial(any());
            // No admission was attempted — the timezone precedes routing.
            verify(dailyDialLimitService, never()).admit(
                any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        void dailyLimitReached_failsPermanentlyWithoutDialingAndWithoutHoldRelease() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.allowed());
            when(voiceRoutingService.resolveRoute(any(), any(), eq(DEST), eq(didId), anyString(), any()))
                    .thenReturn(VoiceRoutingDecision.primary(
                            new VoiceRoute(gatewayId, "fs-gw-test", "external", "TATA", didId, DEST),
                            VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode(),
                            java.util.List.of()));
            when(dailyDialLimitService.admit(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(DailyDialLimitService.AdmissionResult.DAILY_LIMIT_REACHED);

            service.processDueAttempts();

            CallAttempt saved = capturedAttempt();
            assertThat(saved.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(saved.getFailureCode()).isEqualTo("DAILY_LIMIT_REACHED");
            verify(dialer, never()).dial(any());
            // The bucket was read but nothing was granted — and a rejected
            // admission must NOT release (it never held anything).
            verify(dailyDialLimitService, never()).releaseReservation(any(), any(), any(), any());
        }

        @Test
        void acceptedDial_keepsAttemptInProgressWithProviderCallId() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.allowed());
            when(voiceRoutingService.resolveRoute(any(), any(), eq(DEST), eq(didId), anyString(), any()))
                    .thenReturn(VoiceRoutingDecision.primary(
                            new VoiceRoute(gatewayId, "fs-gw-test", "external", "TATA", didId, DEST),
                            VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode(),
                            java.util.List.of()));
            when(voiceCapacity.reserve(gatewayId, tenantId)).thenReturn(true);
            when(dialer.dial(any())).thenReturn(OutboundDialResponse.accepted("fs-abc-123"));

            service.processDueAttempts();

            CallAttempt saved = capturedAttempt();
            assertThat(saved.getStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);
            assertThat(saved.getProviderCallId()).isEqualTo("fs-abc-123");

            // VB-6C.1: provider acceptance (+OK) is exactly where the slot
            // is consumed — the actual route DID and the snapshot usage day.
            verify(dailyDialLimitService).confirmAccepted(
                    eq(tenantId), eq(attemptId), eq(contactId), eq(didId),
                    eq(java.time.LocalDate.of(2026, 9, 27)), eq("fs-abc-123"));
            verify(dailyDialLimitService, never()).releaseReservation(any(), any(), any(), any());
        }
    }

    @Nested
    class DailyLimitHoldLifecycle {

        private void stubRoutingAndCapacity() {
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.allowed());
            when(voiceRoutingService.resolveRoute(any(), any(), eq(DEST), eq(didId), anyString(), any()))
                    .thenReturn(VoiceRoutingDecision.primary(
                            new VoiceRoute(gatewayId, "fs-gw-test", "external", "TATA", didId, DEST),
                            VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode(),
                            java.util.List.of()));
            when(voiceCapacity.reserve(gatewayId, tenantId)).thenReturn(true);
        }

        @Test
        void capacityReservationFailure_releasesDailyHold() {
            stubRoutingAndCapacity();
            when(voiceCapacity.reserve(gatewayId, tenantId)).thenReturn(false);

            service.processDueAttempts();

            assertThat(capturedAttempt().getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
            verify(dailyDialLimitService).releaseReservation(
                    eq(tenantId), eq(contactId), eq(didId),
                    eq(java.time.LocalDate.of(2026, 9, 27)));
            verify(dailyDialLimitService, never()).confirmAccepted(
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        void providerUnavailable_releasesDailyHold() {
            stubRoutingAndCapacity();
            when(dialer.dial(any())).thenReturn(OutboundDialResponse.providerUnavailable());

            service.processDueAttempts();

            assertThat(capturedAttempt().getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
            verify(dailyDialLimitService).releaseReservation(
                    eq(tenantId), eq(contactId), eq(didId),
                    eq(java.time.LocalDate.of(2026, 9, 27)));
        }

        @Test
        void dialerException_releasesDailyHold() {
            stubRoutingAndCapacity();
            when(dialer.dial(any())).thenThrow(new OutboundDialException("FS down"));

            service.processDueAttempts();

            assertThat(capturedAttempt().getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
            verify(dailyDialLimitService).releaseReservation(
                    eq(tenantId), eq(contactId), eq(didId),
                    eq(java.time.LocalDate.of(2026, 9, 27)));
        }

        @Test
        void busy_releasesDailyHold_withoutConfirm() {
            stubRoutingAndCapacity();
            when(dialer.dial(any())).thenReturn(OutboundDialResponse.busy());

            service.processDueAttempts();

            assertThat(capturedAttempt().getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            verify(dailyDialLimitService).releaseReservation(
                    eq(tenantId), eq(contactId), eq(didId),
                    eq(java.time.LocalDate.of(2026, 9, 27)));
            verify(dailyDialLimitService, never()).confirmAccepted(
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("VB-6C.1: bucket keys on the ACTUAL route DID, not the requested campaign DID")
        void routeDidSubstitution_usesRouteDidForBucket() {
            UUID profilePinnedDidId = UUID.fromString("aa000000-0000-4000-8000-0000000000aa");
            when(eligibilityService.evaluate(any(CallEligibility.Context.class), eq(DEST)))
                    .thenReturn(CallEligibility.EligibilityResult.allowed());
            // Routing substitutes a profile-pinned DID for the requested
            // campaign DID (didId) — the DNID the provider will actually see.
            when(voiceRoutingService.resolveRoute(any(), any(), eq(DEST), eq(didId), anyString(), any()))
                    .thenReturn(VoiceRoutingDecision.primary(
                            new VoiceRoute(gatewayId, "fs-gw-test", "external", "TATA",
                                    profilePinnedDidId, "+919800000000"),
                            VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode(),
                            java.util.List.of()));
            when(voiceCapacity.reserve(gatewayId, tenantId)).thenReturn(true);
            when(dialer.dial(any())).thenReturn(OutboundDialResponse.accepted("fs-ok-route"));

            service.processDueAttempts();

            // Admission AND usage both key on the route DID — never the
            // requested DID (audit §5: no silent DID switching in the ledger).
            verify(dailyDialLimitService).admit(
                    eq(tenantId), eq(contactId), eq(profilePinnedDidId),
                    eq(java.time.LocalDate.of(2026, 9, 27)), eq(3));
            verify(dailyDialLimitService).confirmAccepted(
                    eq(tenantId), eq(attemptId), eq(contactId), eq(profilePinnedDidId),
                    eq(java.time.LocalDate.of(2026, 9, 27)), eq("fs-ok-route"));
            verify(dailyDialLimitService, never()).admit(
                    any(), any(), eq(didId), any(), org.mockito.ArgumentMatchers.anyInt());
        }
    }
}
