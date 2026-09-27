package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * VB-1 retry-semantics tests (P21–P23): failed attempts flow through the
 * existing {@code processRetries} policy; permanent configuration failures
 * (PLAYBACK_CONFIG_INVALID etc.) never create retry attempts; successful
 * calls are never retried.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlayfileRetrySemanticsTest {

    private static final UUID TENANT =
            UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID CAMPAIGN_ID =
            UUID.fromString("ce000000-0000-4000-8000-0000000000e1");
    private static final UUID EXECUTION_ID =
            UUID.fromString("ce000000-0000-4000-8000-0000000000e2");
    private static final UUID CONTACT_ID =
            UUID.fromString("ce000000-0000-4000-8000-0000000000e3");

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignExecutionRepository executionRepository;
    @Mock
    private CallAttemptRepository attemptRepository;
    @Mock
    private com.shivang.obd.contact.ContactRepository contactRepository;
    @Mock
    private com.shivang.obd.did.DidRepository didRepository;
    @Mock
    private com.shivang.obd.authz.AuthorizationService authorizationService;
    @Mock
    private com.shivang.obd.security.CurrentUserProvider currentUserProvider;
    @Mock
    private CampaignReadinessService readinessService;
    @Mock
    private com.shivang.obd.tenant.TenantRepository tenantRepository;
    @Mock
    private OutboundDialService dialService;
    @Mock
    private EslEventProcessor eslEventProcessor;
    @Mock
    private CampaignRuntimeConfigResolver runtimeConfigResolver;

    private CampaignExecutionOrchestrator orchestrator;
    private CampaignEntity campaign;
    private CampaignExecution execution;
    private CallAttempt failedAttempt;

    @BeforeEach
    void setUp() {
        orchestrator = new CampaignExecutionOrchestrator(
                campaignRepository, executionRepository, attemptRepository,
                contactRepository,
                // VB-6B.1: audience membership bridge.
                org.mockito.Mockito.mock(com.shivang.obd.contact.ContactGroupMemberRepository.class),
                // Real canonical validator (VB-5E) over the mocked DID repo.
                new com.shivang.obd.campaign.CampaignResourceValidationService(
                        didRepository, null, null),
                authorizationService,
                currentUserProvider, readinessService, tenantRepository,
                runtimeConfigResolver, dialService, eslEventProcessor);

        campaign = new CampaignEntity();
        campaign.setId(CAMPAIGN_ID);
        campaign.setTenantId(TENANT);
        campaign.setContactGroupId(UUID.randomUUID());
        campaign.setDidId(UUID.fromString("ce000000-0000-4000-8000-0000000000e4"));
        // schedule null → adjustToScheduleWindow passes the time through.
        RetryPolicySpec retryPolicy = new RetryPolicySpec();
        retryPolicy.setMaxAttempts(2);
        retryPolicy.setIntervalSeconds(60);
        retryPolicy.setStrategy(RetryStrategy.FIXED);
        campaign.setRetryPolicy(retryPolicy);
        when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(CAMPAIGN_ID, TENANT))
                .thenReturn(Optional.of(campaign));

        execution = new CampaignExecution();
        execution.setId(EXECUTION_ID);
        execution.setTenantId(TENANT);
        execution.setCampaignId(CAMPAIGN_ID);
        execution.setStatus(CampaignExecutionStatus.RUNNING);
        when(executionRepository.findByStatusAndDeletedAtIsNull(CampaignExecutionStatus.RUNNING))
                .thenReturn(List.of(execution));

        failedAttempt = new CallAttempt();
        failedAttempt.setId(UUID.randomUUID());
        failedAttempt.setExecutionId(EXECUTION_ID);
        failedAttempt.setCampaignId(CAMPAIGN_ID);
        failedAttempt.setTenantId(TENANT);
        failedAttempt.setContactId(CONTACT_ID);
        failedAttempt.setAttemptNumber(1);
        failedAttempt.setStatus(CallAttemptStatus.FAILED);
        failedAttempt.setCompletedAt(Instant.now());
        when(attemptRepository.findByExecutionIdAndStatusInAndDeletedAtIsNull(
                EXECUTION_ID, Set.of(CallAttemptStatus.FAILED)))
                .thenReturn(List.of(failedAttempt));

        // VB-6A correction: retries consume the execution's immutable snapshot;
        // the mocked resolver reflects the campaign fixture (same values) and
        // is always invoked execution-only — no live campaign ever reaches it.
        when(runtimeConfigResolver.resolve(
                org.mockito.ArgumentMatchers.any(CampaignExecution.class)))
                .thenAnswer(inv -> {
                    CampaignExecution execution = inv.getArgument(0);
                    return new CampaignRuntimeConfigResolver.CampaignRuntimeConfig(
                            execution.getCampaignId(), campaign.getCampaignType(),
                            campaign.getContactGroupId(), campaign.getDidId(),
                            campaign.getContentMode(), campaign.getAudioAssetId(),
                            campaign.getTtsTemplateId(), campaign.getCallOnWhitelistNumbers(),
                            campaign.getRetryPolicy(), campaign.getSchedule(),
                            com.shivang.obd.campaign.config.ConfigSchemaVersion.V1,
                            null, campaign.getDailyDialLimit());
                });

        // Retry-existence guard: no retry exists yet.
        when(attemptRepository.existsByExecutionIdAndContactIdAndAttemptNumberAndDeletedAtIsNull(
                EXECUTION_ID, CONTACT_ID, 2)).thenReturn(false);

        // Resource-validity guards pass by default (VB-6B.1: identity-scoped).
        when(contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                any(UUID.class), any(UUID.class))).thenReturn(Optional.of(new com.shivang.obd.contact.ContactEntity() {
                    { setTenantId(TENANT); }
                }));
        when(didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                any(UUID.class), any(UUID.class), any(), any())).thenReturn(true);
    }

    @Nested
    class RetryClassification {

        @Test
        @DisplayName("P21: temporary PLAYBACK_FAILED attempt follows the existing retry policy")
        void p21_temporaryPlaybackFailureIsRetried() {
            failedAttempt.setFailureCode("PLAYBACK_FAILED");

            orchestrator.processRetries();

            ArgumentCaptor<CallAttempt> captor = ArgumentCaptor.forClass(CallAttempt.class);
            verify(attemptRepository).save(captor.capture());
            CallAttempt retry = captor.getValue();
            assertThat(retry.getAttemptNumber()).isEqualTo(2);
            assertThat(retry.getStatus()).isEqualTo(CallAttemptStatus.QUEUED);
            assertThat(retry.getContactId()).isEqualTo(CONTACT_ID);
        }

        @Test
        @DisplayName("P22: permanent PLAYBACK_CONFIG_INVALID attempt is never retried")
        void p22_configInvalidNeverRetried() {
            failedAttempt.setFailureCode("PLAYBACK_CONFIG_INVALID");

            orchestrator.processRetries();

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("temporary hangup codes (BUSY) remain retryable under the same policy")
        void temporaryHangupCodesRemainRetryable() {
            failedAttempt.setFailureCode("BUSY");

            orchestrator.processRetries();

            verify(attemptRepository).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("max attempts exhausted → no retry regardless of failure class")
        void maxAttemptsExhausted() {
            failedAttempt.setFailureCode("PLAYBACK_FAILED");
            // maxTotalAttempts = 1 + 2 = 3; attempt 3 has no next retry.
            failedAttempt.setAttemptNumber(3);

            orchestrator.processRetries();

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("VB-6C.1: DAILY_LIMIT_REACHED is never retried — no same-day retry loop")
        void dailyLimitReachedNeverRetriedSameDay() {
            failedAttempt.setFailureCode(CallFailureCode.DAILY_LIMIT_REACHED.name());

            orchestrator.processRetries();

            // The bucket only empties at the next calendar day or via a
            // different DNID, so a same-day retry with the same snapshot
            // configuration can never succeed — the permanent-failure gate
            // must withhold it (deliberate TEMPORARY-like-but-PERMANENT
            // semantics of the code, preserved here).
            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }

        @Test
        @DisplayName("VB-6C.1: EXECUTION_TIMEZONE_INVALID is never retried")
        void executionTimezoneInvalidNeverRetried() {
            failedAttempt.setFailureCode(CallFailureCode.EXECUTION_TIMEZONE_INVALID.name());

            orchestrator.processRetries();

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }
    }

    @Nested
    class NoRetryForSuccess {

        @Test
        @DisplayName("COMPLETED attempts are never considered for retry (P21 precondition)")
        void completedAttemptsNeverRetried() {
            // The retry loop only queries FAILED attempts; simulate a
            // completed call by returning an empty failed set.
            when(attemptRepository.findByExecutionIdAndStatusInAndDeletedAtIsNull(
                    EXECUTION_ID, Set.of(CallAttemptStatus.FAILED)))
                    .thenReturn(List.of());

            orchestrator.processRetries();

            verify(attemptRepository, never()).save(any(CallAttempt.class));
        }
    }
}
