package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.campaign.CampaignRuntimeConfigResolver.CampaignRuntimeConfig;
import com.shivang.obd.campaign.config.ConfigSchemaVersion;
import com.shivang.obd.contact.ContactEntity;
import com.shivang.obd.contact.ContactRepository;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.media.OutboundDialRequest;
import com.shivang.obd.voice.media.OutboundDialResponse;
import com.shivang.obd.voice.media.OutboundDialer;
import com.shivang.obd.voice.routing.VoiceRoutingService;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-6E â€” PAUSED must actually pause dispatch.
 *
 * <h2>The defect</h2>
 *
 * <p>The VB-6E audit found {@code CampaignStatus.PAUSED} was cosmetic: the only
 * runtime reader of campaign status was
 * {@code CampaignReadinessService.checkLifecycleState}, which is reached solely
 * from execution start and from REST. Neither the retry pass nor
 * {@code processDueAttempts} ever looked at it, so a paused campaign's queued
 * attempts kept being dialled and its failed attempts kept being retried.
 *
 * <p>These tests drive the real dispatch pipeline and prove: nothing is
 * dispatched while paused, no safety budget is consumed by the pause itself,
 * the attempt is requeued rather than failed (so resuming works and no retry
 * budget is spent), and resuming dispatches again.
 */
class OutboundDialServicePausedTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");
    private static final UUID CAMPAIGN_ID = UUID.fromString("cccccccc-0000-4000-8000-00000000000c");
    private static final UUID ATTEMPT_ID = UUID.fromString("aaaaaaaa-1111-4000-8000-000000000001");
    private static final UUID CONTACT_ID = UUID.fromString("cccccccc-1111-4000-8000-000000000001");
    private static final UUID EXECUTION_ID = UUID.fromString("eeeeeeee-1111-4000-8000-000000000001");
    private static final String PHONE = "+919800000001";

    private CallAttemptRepository attemptRepository;
    private ContactRepository contactRepository;
    private CampaignRepository campaignRepository;
    private CampaignRuntimeConfigResolver runtimeConfigResolver;
    private CampaignExecutionRepository executionRepository;
    private CallEligibility eligibility;
    private VoiceRoutingService routing;
    private VoiceCapacityService capacity;
    private OutboundDialer dialer;
    private DailyDialLimitService dialLimitService;
    private DailyAttemptSafetyService attemptSafety;
    private OutboundDialService service;

    private CampaignEntity campaign;
    private CallAttempt attempt;

    @BeforeEach
    void setUp() {
        attemptRepository = mock(CallAttemptRepository.class);
        contactRepository = mock(ContactRepository.class);
        campaignRepository = mock(CampaignRepository.class);
        eligibility = mock(CallEligibility.class);
        routing = mock(VoiceRoutingService.class);
        capacity = mock(VoiceCapacityService.class);
        dialer = mock(OutboundDialer.class);
        dialLimitService = mock(DailyDialLimitService.class);
        attemptSafety = mock(DailyAttemptSafetyService.class);
        runtimeConfigResolver = mock(CampaignRuntimeConfigResolver.class);
        executionRepository = mock(CampaignExecutionRepository.class);

        // The due-attempt query is the scheduler's entry point. Without this
        // stub the mock returns an empty list and every assertion below would
        // pass vacuously, because nothing would ever be dispatched.
        when(attemptRepository
                .findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(
                        eq(CallAttemptStatus.QUEUED), any(java.time.Instant.class)))
                .thenAnswer(invocation -> java.util.List.of(currentAttempt()));

        campaign = new CampaignEntity();
        campaign.setId(CAMPAIGN_ID);
        campaign.setTenantId(TENANT);
        campaign.setStatus(CampaignStatus.SCHEDULED);
        when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(CAMPAIGN_ID, TENANT))
                .thenReturn(Optional.of(campaign));

        ContactEntity contact = new ContactEntity();
        contact.setId(CONTACT_ID);
        contact.setTenantId(TENANT);
        contact.setPhoneNumber(PHONE);
        lenient().when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(CONTACT_ID, TENANT))
                .thenReturn(Optional.of(contact));

        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attemptOf(CallAttemptStatus.QUEUED)));
        lenient().when(dialLimitService.resolveUsageDate(anyString()))
                .thenReturn(LocalDate.of(2026, 9, 27));
        lenient().when(dialLimitService.effectiveLimit(any())).thenReturn(3);
        lenient().when(dialLimitService.admit(any(), any(), any(), any(), anyInt()))
                .thenReturn(DailyDialLimitService.AdmissionResult.ADMITTED);
        lenient().when(attemptSafety.admit(any(), any(), anyString(), any()))
                .thenReturn(DailyAttemptSafetyService.AdmissionResult.ADMITTED);

        // The dial path resolves configuration from the immutable snapshot. A
        // real config is required: a null here NPEs before eligibility and the
        // pause gate would never be reached, so the test would prove nothing.
        CampaignExecution execution = new CampaignExecution();
        execution.setId(EXECUTION_ID);
        execution.setTenantId(TENANT);
        execution.setCampaignId(CAMPAIGN_ID);
        // VB-8D: the dial path now refuses a terminal execution before any
        // dispatch work, so a live fixture must actually be live.
        execution.setStatus(CampaignExecutionStatus.RUNNING);
        when(executionRepository.findByIdAndDeletedAtIsNull(EXECUTION_ID))
                .thenReturn(Optional.of(execution));
        when(runtimeConfigResolver.resolve(any()))
                .thenReturn(snapshotConfig());

        // VB-8D: the dial step now claims each due attempt with one conditional
        // QUEUED -> IN_PROGRESS update before it dispatches, then re-reads it.
        // This mirrors the database faithfully - the claim really moves the row -
        // so the tests keep asserting on the same fixture object they always did.
        lenient().when(attemptRepository.claimForDispatch(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(CallAttemptStatus.QUEUED),
                        org.mockito.ArgumentMatchers.eq(CallAttemptStatus.IN_PROGRESS),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    CallAttempt claimed = currentAttempt();
                    if (claimed.getStatus() != CallAttemptStatus.QUEUED) {
                        return 0;
                    }
                    claimed.setStatus(CallAttemptStatus.IN_PROGRESS);
                    claimed.setStartedAt(java.time.Instant.now());
                    return 1;
                });
        lenient().when(attemptRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> Optional.ofNullable(currentAttempt()));

        service = new OutboundDialService(
                attemptRepository, contactRepository,
                mock(TenantRepository.class), campaignRepository,
                executionRepository,
                runtimeConfigResolver, dialer, eligibility, routing,
                capacity, mock(CallSessionRepository.class), mock(CallLegRepository.class),
                dialLimitService, attemptSafety, new PreDispatchFailureMapper(), TransactionTestSupport.direct(), new ExecutionScheduleCalculator());
    }

    /** A valid frozen snapshot config: PLAYFILE, AUDIO, with a timezone. */
    private CampaignRuntimeConfig snapshotConfig() {
        ScheduleSpec schedule = new ScheduleSpec();
        schedule.setTimezone("UTC");
        RetryPolicySpec retry = new RetryPolicySpec();
        retry.setMaxAttempts(0);
        retry.setIntervalSeconds(60);
        retry.setStrategy(RetryStrategy.FIXED);
        return new CampaignRuntimeConfig(CAMPAIGN_ID, CampaignType.PLAYFILE, null, null,
                ContentMode.AUDIO, null, null, false, retry, schedule,
                com.shivang.obd.campaign.config.ConfigSchemaVersion.V1, null, null, null, null);
    }

    /** The attempt the tick picks up; the tests replace it per scenario. */
    private CallAttempt currentAttempt() {
        return attempt == null ? attemptOf(CallAttemptStatus.QUEUED) : attempt;
    }

    private CallAttempt attemptOf(CallAttemptStatus status) {
        CallAttempt a = new CallAttempt();
        a.setId(ATTEMPT_ID);
        a.setTenantId(TENANT);
        a.setCampaignId(CAMPAIGN_ID);
        a.setContactId(CONTACT_ID);
        a.setExecutionId(EXECUTION_ID);
        a.setAttemptNumber(1);
        a.setStatus(status);
        a.setScheduledAt(java.time.Instant.now());
        return a;
    }

    /** The snapshot the dial path resolves: a valid PLAYFILE/AUDIO campaign. */

    @Test
    @DisplayName("PAUSE-1: a paused campaign does not dial")
    void pausedCampaignDoesNotDial() {
        attempt = attemptOf(CallAttemptStatus.QUEUED);
        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        campaign.setStatus(CampaignStatus.PAUSED);
        when(eligibility.evaluate(any(CallEligibility.Context.class), anyString()))
                .thenReturn(CallEligibility.EligibilityResult.allowed());

        service.processDueAttempts();

        verify(dialer, never()).dial(any());
        assertThat(attempt.getStatus())
                .as("the attempt must stay recoverable, not be failed")
                .isEqualTo(CallAttemptStatus.QUEUED);
    }

    @Test
    @DisplayName("PAUSE-2: pausing consumes no safety budget at all")
    void pausedCampaignConsumesNoBudget() {
        attempt = attemptOf(CallAttemptStatus.QUEUED);
        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        campaign.setStatus(CampaignStatus.PAUSED);

        service.processDueAttempts();

        // VB-6C holds, VB-6D.3 consumptions and the retry path must all be
        // untouched: a pause is not a dispatch, so it must not be charged as one.
        verify(dialLimitService, never()).admit(any(), any(), any(), any(), anyInt());
        verify(attemptSafety, never()).admit(any(), any(), anyString(), any());
        verify(capacity, never()).reserve(any(), any());
    }

    @Test
    @DisplayName("PAUSE-3: the attempt keeps its number so a resume does not consume retry budget")
    void pausedAttemptKeepsItsNumber() {
        attempt = attemptOf(CallAttemptStatus.QUEUED);
        attempt.setStartedAt(java.time.Instant.now());
        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        campaign.setStatus(CampaignStatus.PAUSED);

        service.processDueAttempts();

        assertThat(attempt.getAttemptNumber())
                .as("an unchanged attempt number means the resume is a first attempt, not a retry")
                .isEqualTo(1);
        assertThat(attempt.getFailureCode())
                .as("a pause must not be recorded as a call failure")
                .isNull();
        assertThat(attempt.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("PAUSE-4: after resuming, the attempt is dispatched normally")
    void resumedCampaignDials() {
        attempt = attemptOf(CallAttemptStatus.QUEUED);
        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        campaign.setStatus(CampaignStatus.PAUSED);

        // First tick: paused. The attempt is requeued BEFORE the compliance
        // gate, so eligibility is not even consulted.
        service.processDueAttempts();
        verify(dialer, never()).dial(any());
        verify(eligibility, never())
                .evaluate(any(CallEligibility.Context.class), anyString());
        assertThat(attempt.getStatus())
                .as("a pause requeues, it does not fail")
                .isEqualTo(CallAttemptStatus.QUEUED);

        // Resume.
        campaign.setStatus(CampaignStatus.SCHEDULED);
        attempt.setStatus(CallAttemptStatus.QUEUED);
        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        when(eligibility.evaluate(any(CallEligibility.Context.class), anyString()))
                .thenReturn(CallEligibility.EligibilityResult.allowed());
        when(routing.resolveRoute(any(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(com.shivang.obd.voice.routing.VoiceRoutingDecision.rejected(
                        "ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY", java.util.List.of()));
        when(capacity.reserve(any(), any())).thenReturn(true);

        // Second tick: the attempt is considered again, and this time it passes
        // the pause gate and runs the rest of the pipeline. The pipeline is
        // mocked beyond the gate, which is all this test claims.
        service.processDueAttempts();

        verify(eligibility, org.mockito.Mockito.times(1))
                .evaluate(any(CallEligibility.Context.class), anyString());
        verify(routing, org.mockito.Mockito.times(1))
                .resolveRoute(any(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("PAUSE-5: a non-paused campaign is not affected by the new gate")
    void runningCampaignUnaffected() {
        attempt = attemptOf(CallAttemptStatus.QUEUED);
        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        campaign.setStatus(CampaignStatus.RUNNING);
        when(eligibility.evaluate(any(CallEligibility.Context.class), anyString()))
                .thenReturn(CallEligibility.EligibilityResult.allowed());
        when(routing.resolveRoute(any(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(com.shivang.obd.voice.routing.VoiceRoutingDecision.rejected(
                        "ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY", java.util.List.of()));

        // Guard: prove the attempt really is visible to the tick, so the
        // assertions below cannot pass vacuously.
        org.assertj.core.api.Assertions.assertThat(attemptRepository
                .findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(
                        CallAttemptStatus.QUEUED, java.time.Instant.now()))
                .as("the queued attempt must be visible to the scheduler")
                .hasSize(1);

        service.processDueAttempts();

        // The pipeline ran past the pause gate.
        verify(eligibility).evaluate(any(CallEligibility.Context.class), anyString());
        verify(attemptSafety, never()).admit(any(), any(), anyString(), any());
    }
}
