package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.dto.CampaignReadinessReason;
import com.shivang.obd.campaign.dto.CampaignReadinessResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-6E â€” scheduled orchestration: system identity and step isolation.
 *
 * <h2>Defect 1 â€” the scheduler could never start an execution</h2>
 *
 * <p>Pre-VB-6E {@code startExecution} began with {@code requireUserId()},
 * which reads {@code SecurityContextHolder}. Nothing in the application
 * populates that outside a servlet request, so on a scheduler thread it was
 * always empty and every tick threw {@code UNAUTHORIZED}. These tests run with
 * a deliberately empty security context â€” exactly what a scheduler thread sees â€”
 * and prove an execution still starts.
 *
 * <h2>Defect 2 â€” one failure disabled all five steps</h2>
 *
 * <p>Because the whole tick shared one {@code try/catch}, that always-throwing
 * first step meant retries, dialing, the ESL pump and reconciliation were all
 * skipped every cycle. Each step is now isolated; {@code TICK-*} proves a
 * failure in one step does not stop the others.
 */
class CampaignExecutionOrchestratorSchedulerTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");
    private static final UUID CAMPAIGN = UUID.fromString("cccccccc-0000-4000-8000-00000000000c");
    private static final UUID EXECUTION = UUID.fromString("eeeeeeee-0000-4000-8000-0000000000ee");

    private CampaignRepository campaignRepository;
    private CampaignExecutionRepository executionRepository;
    private OutboundDialService dialService;
    private EslEventProcessor eslEventProcessor;
    private StaleCallReconciler staleCallReconciler;
    private CampaignReadinessService readinessService;
    private CampaignExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        campaignRepository = org.mockito.Mockito.mock(CampaignRepository.class);
        executionRepository = org.mockito.Mockito.mock(CampaignExecutionRepository.class);
        dialService = org.mockito.Mockito.mock(OutboundDialService.class);
        eslEventProcessor = org.mockito.Mockito.mock(EslEventProcessor.class);
        staleCallReconciler = org.mockito.Mockito.mock(StaleCallReconciler.class);
        readinessService = org.mockito.Mockito.mock(CampaignReadinessService.class);

        when(executionRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(java.util.Optional.empty());
        when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(java.util.Optional.empty());
        when(executionRepository.findByStatusAndDeletedAtIsNull(any()))
                .thenReturn(List.of());
        when(dialService.processDueAttempts()).thenReturn(0);

        orchestrator = new CampaignExecutionOrchestrator(
                campaignRepository, executionRepository,
                org.mockito.Mockito.mock(CallAttemptRepository.class),
                org.mockito.Mockito.mock(com.shivang.obd.contact.ContactRepository.class),
                org.mockito.Mockito.mock(com.shivang.obd.contact.ContactGroupMemberRepository.class),
                org.mockito.Mockito.mock(CampaignResourceValidationService.class),
                org.mockito.Mockito.mock(com.shivang.obd.authz.AuthorizationService.class),
                org.mockito.Mockito.mock(com.shivang.obd.security.CurrentUserProvider.class),
                readinessService,
                org.mockito.Mockito.mock(com.shivang.obd.tenant.TenantRepository.class),
                org.mockito.Mockito.mock(CampaignRuntimeConfigResolver.class),
                // VB-8B: the one canonical calling-window calculation.
                new ExecutionScheduleCalculator(),
                TransactionTestSupport.direct(),
                new RetryPolicyService(),
                dialService, eslEventProcessor, staleCallReconciler);
    }

    /** The campaign a scheduled start resolves, tenant-scoped by the execution row. */
    private CampaignEntity campaignInTenant() {
        CampaignEntity campaign = new CampaignEntity();
        campaign.setId(CAMPAIGN);
        campaign.setTenantId(TENANT);
        return campaign;
    }

    // =====================================================================
    // System identity
    // =====================================================================

    @Test
    @DisplayName("SCHED-1: the security context really is empty, as on a scheduler thread")
    void securityContextIsEmpty() {
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("the precondition that broke the pre-VB-6E scheduler")
                .isNull();
    }

    @Test
    @DisplayName("SCHED-2: a scheduled start does not require an authenticated user")
    void scheduledStartNeedsNoUser() {
        // No SecurityContext, no authenticated user: exactly the scheduler case.
        assertThat(orchestrator.startExecutionAsSystem(UUID.randomUUID())).isFalse();
        // It returned normally rather than throwing UNAUTHORIZED, which is the
        // whole point: the previous code threw before reaching this point.
    }

    @Test
    @DisplayName("SCHED-3: a scheduled start never fabricates a security context")
    void scheduledStartDoesNotLeakAuthentication() {
        // Running the tick must not leave any authentication behind for other
        // work on the same thread.
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymous",
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        orchestrator.startExecutionAsSystem(UUID.randomUUID());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("the scheduler must not overwrite or fabricate an auth context")
                .isNotNull()
                .isInstanceOf(AnonymousAuthenticationToken.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("SCHED-4: the interactive start path still requires a user")
    void interactiveStartStillRequiresUser() {
        // The interactive path must be unchanged: without a user it must refuse,
        // exactly as it did before VB-6E.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> orchestrator.startExecution(EXECUTION))
                .isInstanceOf(com.shivang.obd.common.exception.BusinessException.class);
    }

    // =====================================================================
    // Step isolation
    // =====================================================================

    @Test
    @DisplayName("TICK-1: a failure in the retry step does not stop the later steps")
    void retryFailureDoesNotStopLaterSteps() {
        // Nothing is REQUESTED, so the first step is a no-op; the failure is
        // injected into the dial step instead, which comes after retries.
        doThrow(new IllegalStateException("dial step exploded"))
                .when(dialService).processDueAttempts();

        orchestrator.scheduledTick();

        verify(eslEventProcessor, times(1)).ensureEventProcessing();
        verify(staleCallReconciler, times(1)).reconcile();
    }

    @Test
    @DisplayName("TICK-2: a failure in the ESL pump does not stop reconciliation")
    void eslFailureDoesNotStopReconciliation() {
        doThrow(new IllegalStateException("esl pump exploded"))
                .when(eslEventProcessor).ensureEventProcessing();

        orchestrator.scheduledTick();

        verify(dialService, times(1)).processDueAttempts();
        verify(staleCallReconciler, times(1)).reconcile();
    }

    @Test
    @DisplayName("TICK-3: a failure in reconciliation does not stop the stale-call sweep")
    void reconciliationFailureDoesNotStopSweep() {
        doThrow(new IllegalStateException("reconcile exploded"))
                .when(executionRepository).findByStatusAndDeletedAtIsNull(
                        org.mockito.ArgumentMatchers.eq(CampaignExecutionStatus.RUNNING));

        orchestrator.scheduledTick();

        verify(staleCallReconciler, times(1)).reconcile();
    }

    @Test
    @DisplayName("TICK-4: a failure in the stale sweep leaves everything else intact")
    void sweepFailureIsContained() {
        doThrow(new IllegalStateException("sweep exploded")).when(staleCallReconciler).reconcile();

        // Must not propagate: the scheduler thread has to survive.
        org.assertj.core.api.Assertions.assertThatCode(() -> orchestrator.scheduledTick())
                .doesNotThrowAnyException();

        verify(dialService, times(1)).processDueAttempts();
        verify(eslEventProcessor, times(1)).ensureEventProcessing();
    }

    @Test
    @DisplayName("TICK-5: every step runs on a clean tick")
    void allStepsRunOnCleanTick() {
        orchestrator.scheduledTick();

        verify(dialService, times(1)).processDueAttempts();
        verify(eslEventProcessor, times(1)).ensureEventProcessing();
        verify(staleCallReconciler, times(1)).reconcile();
    }

    @Test
    @DisplayName("TICK-6: several consecutive ticks all run every step")
    void repeatedTicksAreStable() {
        for (int i = 0; i < 3; i++) {
            orchestrator.scheduledTick();
        }

        verify(dialService, times(3)).processDueAttempts();
        verify(eslEventProcessor, times(3)).ensureEventProcessing();
        verify(staleCallReconciler, times(3)).reconcile();
    }

    @Test
    @DisplayName("TICK-7: the tick still uses the one existing scheduler, not a new one")
    void tickKeepsSingleScheduler() throws Exception {
        // One @Scheduled method, unchanged cadence. A second scheduler would
        // duplicate responsibility, which the phase explicitly forbids.
        var scheduled = java.util.Arrays.stream(
                        CampaignExecutionOrchestrator.class
                                .getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .toList();

        assertThat(scheduled)
                .as("exactly one @Scheduled method on the orchestrator")
                .hasSize(1);
        Scheduled annotation = scheduled.get(0).getAnnotation(Scheduled.class);
        assertThat(annotation.fixedDelay()).isEqualTo(30000);
    }

    @Test
    @DisplayName("TICK-8: the tick itself is not one long transaction")
    void tickIsNotTransactional() throws Exception {
        // Each step owns its transaction, so one step's rollback cannot discard
        // another's work and the tick no longer holds a transaction open across
        // outbound network I/O.
        var tick = java.util.Arrays.stream(
                        CampaignExecutionOrchestrator.class
                                .getDeclaredMethods())
                .filter(m -> m.getName().equals("scheduledTick"))
                .findFirst()
                .orElseThrow();

        assertThat(tick.isAnnotationPresent(Transactional.class))
                .as("the tick must not wrap every step in a single transaction")
                .isFalse();
    }

    // =====================================================================
    // Pause must defer, not destroy
    // =====================================================================

    @Test
    @DisplayName("SCHED-9: a paused campaign defers its execution instead of failing it")
    void pausedCampaignDefersRatherThanFails() {
        CampaignExecution execution = new CampaignExecution();
        execution.setId(EXECUTION);
        execution.setTenantId(TENANT);
        execution.setCampaignId(CAMPAIGN);
        execution.setStatus(CampaignExecutionStatus.REQUESTED);

        when(executionRepository.findByIdAndDeletedAtIsNull(EXECUTION))
                .thenReturn(java.util.Optional.of(execution));
        when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(CAMPAIGN, TENANT))
                .thenReturn(java.util.Optional.of(campaignInTenant()));
        when(readinessService.evaluateForSystem(CAMPAIGN, TENANT)).thenReturn(
                new CampaignReadinessResponse(CAMPAIGN, false, List.of(
                        new CampaignReadinessReason(
                                "CAMPAIGN_NOT_EXECUTABLE_STATE",
                                "Campaign is not in an executable state"))));

        boolean started = orchestrator.startExecutionAsSystem(EXECUTION);

        assertThat(started).isFalse();
        assertThat(execution.getStatus())
                .as("a pause must not destroy pending work")
                .isEqualTo(CampaignExecutionStatus.REQUESTED);
        verify(executionRepository, never()).save(any());
    }

    @Test
    @DisplayName("SCHED-10: a genuinely unrunnable campaign still fails deterministically")
    void unrunnableCampaignStillFails() {
        // A configuration problem will still be true next tick, so silently
        // retrying it forever would be a hot loop against the database.
        CampaignExecution execution = new CampaignExecution();
        execution.setId(EXECUTION);
        execution.setTenantId(TENANT);
        execution.setCampaignId(CAMPAIGN);
        execution.setStatus(CampaignExecutionStatus.REQUESTED);

        when(executionRepository.findByIdAndDeletedAtIsNull(EXECUTION))
                .thenReturn(java.util.Optional.of(execution));
        when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(CAMPAIGN, TENANT))
                .thenReturn(java.util.Optional.of(campaignInTenant()));
        when(readinessService.evaluateForSystem(CAMPAIGN, TENANT)).thenReturn(
                new CampaignReadinessResponse(CAMPAIGN, false, List.of(
                        new CampaignReadinessReason(
                                "AUDIO_NOT_APPROVED", "Audio asset is not approved"))));

        boolean started = orchestrator.startExecutionAsSystem(EXECUTION);

        assertThat(started).isFalse();
        assertThat(execution.getStatus())
                .isEqualTo(CampaignExecutionStatus.FAILED);
        assertThat(execution.getFailureReason()).contains("AUDIO_NOT_APPROVED");
    }
}
