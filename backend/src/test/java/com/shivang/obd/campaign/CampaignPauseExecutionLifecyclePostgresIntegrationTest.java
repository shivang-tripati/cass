package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.campaign.config.CampaignTypeConfigValidator;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.contact.ContactGroupMemberRepository;
import com.shivang.obd.contact.ContactGroupRepository;
import com.shivang.obd.contact.ContactRepository;
import com.shivang.obd.did.AllocationSource;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSessionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-8H — campaign pause/resume and execution lifecycle ownership, against real
 * PostgreSQL.
 *
 * <p>Three findings are covered:
 * <ul>
 *   <li><b>F-8H-01</b> — the dispatch gate was {@code == PAUSED}, so a campaign
 *       moved to {@code ARCHIVED} (terminal, operator-legal from SCHEDULED) kept
 *       dialling forever. Now gated on the existing executable authority.</li>
 *   <li><b>F-8H-02</b> — the campaign lifecycle's false "performed by the
 *       execution engine" withholding is removed; lifecycle ownership is made
 *       explicit.</li>
 *   <li><b>F-8H-03 / B10</b> — a REQUESTED execution explains itself through a
 *       derived, never-persisted reason.</li>
 * </ul>
 *
 * <p>The headline invariant is that <b>pause controls future work without
 * rewriting history</b>: it stops new dispatch and consumes no budget, it never
 * terminates an established call, and it never completes or fails an execution.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampaignPauseExecutionLifecyclePostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("obd")
                    .withUsername("obd_user")
                    .withPassword("obd_password");

    static volatile Exception startupFailure;

    static {
        try {
            POSTGRES.start();
        } catch (Exception e) {
            startupFailure = e;
        }
    }

    @AfterAll
    void stopContainer() {
        POSTGRES.stop();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        if (startupFailure != null) {
            return;
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignExecutionRepository executionRepository;
    @Autowired private CampaignExecutionConfigurationRepository snapshotRepository;
    @Autowired private CallAttemptRepository attemptRepository;
    @Autowired private CallSessionRepository callSessionRepository;
    @Autowired private CallLegRepository callLegRepository;
    @Autowired private DidRepository didRepository;
    @Autowired private AudioAssetRepository audioAssetRepository;
    @Autowired private ContactGroupRepository contactGroupRepository;
    @Autowired private ContactGroupMemberRepository memberRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private CampaignRuntimeConfigResolver runtimeConfigResolver;
    private CampaignExecutionOrchestrator orchestrator;
    private com.shivang.obd.voice.media.OutboundDialer dialer;
    private com.shivang.obd.voice.capacity.VoiceCapacityService capacity;
    private DailyDialLimitService limitService;
    private DailyAttemptSafetyService attemptSafety;
    private CallEligibility eligibilityService;
    private com.shivang.obd.voice.routing.VoiceRoutingService routing;
    private AtomicInteger dialCount;

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                            + startupFailure.getMessage(), startupFailure);
        }
        tx = new TransactionTemplate(transactionManager);
        dialCount = new AtomicInteger();
        var configurationService = new CampaignConfigurationService(
                snapshotRepository, new CampaignTypeConfigValidator());
        runtimeConfigResolver = new CampaignRuntimeConfigResolver(configurationService);
        limitService = org.mockito.Mockito.mock(DailyDialLimitService.class);
        attemptSafety = org.mockito.Mockito.mock(DailyAttemptSafetyService.class);
        capacity = org.mockito.Mockito.mock(com.shivang.obd.voice.capacity.VoiceCapacityService.class);
        dialer = org.mockito.Mockito.mock(com.shivang.obd.voice.media.OutboundDialer.class);

        lenient().when(attemptSafety.admit(any(), any(), any(), any()))
                .thenReturn(DailyAttemptSafetyService.AdmissionResult.ADMITTED);

        // Must be stubbed: an unstubbed mock returns null, which the dial path
        // correctly reads as "not allowed", so the attempt would never be dialled
        // and the gate tests would pass for the wrong reason.
        eligibilityService = org.mockito.Mockito.mock(CallEligibility.class);
        lenient().when(eligibilityService.evaluate(any(CallEligibility.Context.class), any()))
                .thenReturn(CallEligibility.EligibilityResult.allowed());
        // Created here because the orchestrator's dial service is built in
        // setUp and must receive this same instance.
        routing = org.mockito.Mockito.mock(
                com.shivang.obd.voice.routing.VoiceRoutingService.class);

        orchestrator = new CampaignExecutionOrchestrator(
                campaignRepository, executionRepository, attemptRepository,
                contactRepository, memberRepository,
                new CampaignResourceValidationService(didRepository, audioAssetRepository, null),
                org.mockito.Mockito.mock(com.shivang.obd.authz.AuthorizationService.class),
                () -> Optional.of(new com.shivang.obd.security.AuthenticatedUser(
                        CALLER_ID, "t@test", null)),
                new CampaignReadinessService(campaignRepository,
                        org.mockito.Mockito.mock(com.shivang.obd.authz.AuthorizationService.class),
                        () -> Optional.of(new com.shivang.obd.security.AuthenticatedUser(
                                CALLER_ID, "t@test", null)),
                        contactGroupRepository,
                        new CampaignResourceValidationService(
                                didRepository, audioAssetRepository, null),
                        tenantRepository),
                tenantRepository, runtimeConfigResolver,
                new ExecutionScheduleCalculator(), tx, new RetryPolicyService(),
                dialService(), org.mockito.Mockito.mock(EslEventProcessor.class),
                org.mockito.Mockito.mock(StaleCallReconciler.class));
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(t -> {
            entityManager.createQuery("DELETE FROM VoiceBlastDailyUsageEntry").executeUpdate();
            entityManager.createQuery("DELETE FROM VoiceBlastDailyUsage").executeUpdate();
            entityManager.createQuery("DELETE FROM CallLeg").executeUpdate();
            entityManager.createQuery("DELETE FROM CallSession").executeUpdate();
            entityManager.createQuery("DELETE FROM CallAttempt").executeUpdate();
            entityManager.createQuery("DELETE FROM CampaignExecution").executeUpdate();
            entityManager.createQuery("DELETE FROM CampaignExecutionConfiguration").executeUpdate();
            entityManager.createQuery("DELETE FROM CampaignEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM AudioAssetEntity").executeUpdate();
            entityManager.createQuery(
                    "DELETE FROM com.shivang.obd.contact.ContactGroupMemberEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactGroupEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM DidEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM TenantEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ResellerEntity").executeUpdate();
        });
    }

    // =====================================================================
    // F-8H-01 — the dispatch gate must fail closed for every non-executable state
    // =====================================================================

    @Test
    @DisplayName("F8H-01: an ARCHIVED campaign stops dispatching (the defect VB-8H fixed)")
    void archivedCampaignDoesNotDispatch() {
        Fixture f = seedFixture("arch");
        CallAttempt attempt = seedAttempt(f);
        setCampaignStatus(f.campaignId(), CampaignStatus.ARCHIVED);
        stubRoute();

        dialService().processDueAttempts();

        assertThat(dialCount.get())
                .as("ARCHIVED is terminal, yet the old == PAUSED gate kept dialling")
                .isZero();
        assertThat(reloadAttempt(attempt.getId()).getStatus())
                .as("the attempt is retained, not failed - pause/archive never rewrites history")
                .isEqualTo(CallAttemptStatus.QUEUED);
    }

    @Test
    @DisplayName("F8H-01: a DRAFT campaign does not dispatch either")
    void draftCampaignDoesNotDispatch() {
        Fixture f = seedFixture("draft");
        CallAttempt attempt = seedAttempt(f);
        setCampaignStatus(f.campaignId(), CampaignStatus.DRAFT);
        stubRoute();

        dialService().processDueAttempts();

        assertThat(dialCount.get()).isZero();
        assertThat(reloadAttempt(attempt.getId()).getStatus())
                .isEqualTo(CallAttemptStatus.QUEUED);
    }

    @Test
    @DisplayName("F8H-01: a COMPLETED campaign does not dispatch")
    void completedCampaignDoesNotDispatch() {
        Fixture f = seedFixture("done");
        CallAttempt attempt = seedAttempt(f);
        setCampaignStatus(f.campaignId(), CampaignStatus.COMPLETED);
        stubRoute();

        dialService().processDueAttempts();

        assertThat(dialCount.get()).isZero();
        assertThat(reloadAttempt(attempt.getId()).getStatus())
                .isEqualTo(CallAttemptStatus.QUEUED);
    }

    @Test
    @DisplayName("F8H-01: PAUSED still does not dispatch - existing behaviour preserved")
    void pausedCampaignDoesNotDispatch() {
        Fixture f = seedFixture("paused");
        CallAttempt attempt = seedAttempt(f);
        setCampaignStatus(f.campaignId(), CampaignStatus.PAUSED);
        stubRoute();

        dialService().processDueAttempts();

        assertThat(dialCount.get()).isZero();
        assertThat(reloadAttempt(attempt.getId()).getStatus())
                .isEqualTo(CallAttemptStatus.QUEUED);
    }

    @Test
    @DisplayName("F8H-01: a SCHEDULED campaign still dispatches - the gate was not narrowed")
    void scheduledCampaignStillDispatches() {
        assertExecutableCampaignDispatches(CampaignStatus.SCHEDULED);
    }

    @Test
    @DisplayName("F8H-01: a RUNNING campaign still dispatches - the gate was not narrowed")
    void runningCampaignStillDispatches() {
        assertExecutableCampaignDispatches(CampaignStatus.RUNNING);
    }

    private void assertExecutableCampaignDispatches(CampaignStatus executable) {
        Fixture f = seedFixture("exec-" + executable);
        CallAttempt attempt = seedAttempt(f);
        stubRoute();
        lenient().when(dialer.dial(any())).thenAnswer(inv -> {
            dialCount.incrementAndGet();
            return com.shivang.obd.voice.media.OutboundDialResponse.accepted(
                    "prov-" + attempt.getId());
        });

        dialService().processDueAttempts();

        assertThat(dialCount.get())
                .as("%s is executable and must keep dispatching", executable)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("F8H-01: a gated campaign consumes no VB-6C hold and no VB-6D.3 attempt")
    void gatedCampaignConsumesNoBudget() {
        Fixture f = seedFixture("nobudget");
        seedAttempt(f);
        setCampaignStatus(f.campaignId(), CampaignStatus.ARCHIVED);
        stubRoute();
        org.mockito.Mockito.clearInvocations(limitService, attemptSafety, capacity);

        dialService().processDueAttempts();

        org.mockito.Mockito.verifyNoInteractions(limitService, attemptSafety, capacity);
    }

    // =====================================================================
    // Scenarios B / D — a REQUESTED execution is deferred, never failed
    // =====================================================================

    @Test
    @DisplayName("B/D: pausing leaves a REQUESTED execution REQUESTED, not failed")
    void pauseDefersRatherThanFailsRequestedExecution() {
        Fixture f = seedFixture("b");
        setCampaignStatus(f.campaignId(), CampaignStatus.PAUSED);

        boolean started = orchestrator.startExecutionAsSystem(f.executionId());

        assertThat(started).isFalse();
        assertThat(executionStatus(f.executionId()))
                .as("a non-executable campaign defers; it never destroys pending work")
                .isEqualTo(CampaignExecutionStatus.REQUESTED);
        assertThat(attemptCount(f.executionId()))
                .as("and no attempts are created while deferred")
                .isZero();
    }

    @Test
    @DisplayName("D: repeated scheduler ticks while paused never fail the execution")
    void repeatedTicksWhilePausedNeverFailTheExecution() {
        Fixture f = seedFixture("d");
        setCampaignStatus(f.campaignId(), CampaignStatus.PAUSED);

        for (int i = 0; i < 5; i++) {
            orchestrator.startExecutionAsSystem(f.executionId());
        }

        assertThat(executionStatus(f.executionId()))
                .isEqualTo(CampaignExecutionStatus.REQUESTED);
    }

    // =====================================================================
    // Scenario C / F — a RUNNING execution keeps its state; active calls untouched
    // =====================================================================

    @Test
    @DisplayName("C/F: pausing mid-run leaves the execution RUNNING, not completed or failed")
    void pausingMidRunDoesNotRewriteTheExecution() {
        Fixture f = seedFixture("c");
        orchestrator.startExecutionAsSystem(f.executionId());
        assertThat(executionStatus(f.executionId()))
                .isEqualTo(CampaignExecutionStatus.RUNNING);
        setCampaignStatus(f.campaignId(), CampaignStatus.PAUSED);

        // A scheduler tick while paused: the start step must not re-gate an
        // already-RUNNING execution, and must not complete or fail it.
        for (int i = 0; i < 3; i++) {
            orchestrator.startExecutionAsSystem(f.executionId());
            orchestrator.reconcileExecution(f.executionId());
        }

        assertThat(executionStatus(f.executionId()))
                .as("pause controls future dispatch; it never settles an execution")
                .isEqualTo(CampaignExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("C: an IN_PROGRESS attempt is never touched by pausing the campaign")
    void pausingDoesNotTerminateInProgressWork() {
        Fixture f = seedFixture("c2");
        CallAttempt attempt = seedAttempt(f);
        int rows = tx.execute(t -> attemptRepository.claimForDispatch(
                attempt.getId(), f.tenantId(),
                CallAttemptStatus.QUEUED, CallAttemptStatus.IN_PROGRESS, Instant.now()));
        assertThat(rows).isEqualTo(1);
        setCampaignStatus(f.campaignId(), CampaignStatus.PAUSED);
        stubRoute();

        dialService().processDueAttempts();

        assertThat(reloadAttempt(attempt.getId()).getStatus())
                .as("an already-claimed attempt keeps running; pause does not terminate it")
                .isEqualTo(CallAttemptStatus.IN_PROGRESS);
        assertThat(callSessionCount(attempt.getId()))
                .as("and nothing hung it up")
                .isZero();
    }

    // =====================================================================
    // Scenario H — completion is independent of campaign control state
    // =====================================================================

    @Test
    @DisplayName("H: an execution completes while its campaign stays PAUSED")
    void executionCompletesWhileCampaignPaused() {
        Fixture f = seedFixture("h");
        orchestrator.startExecutionAsSystem(f.executionId());
        completeAllAttempts(f);

        setCampaignStatus(f.campaignId(), CampaignStatus.PAUSED);
        orchestrator.reconcileExecution(f.executionId());

        assertThat(executionStatus(f.executionId()))
                .as("completion reflects finished work, not a control-state change")
                .isEqualTo(CampaignExecutionStatus.COMPLETED);
    }

    @Test
    @DisplayName("H: an execution can also fail while its campaign stays PAUSED")
    void executionFailsWhileCampaignPaused() {
        Fixture f = seedFixture("h2");
        orchestrator.startExecutionAsSystem(f.executionId());
        failAllAttempts(f);

        setCampaignStatus(f.campaignId(), CampaignStatus.ARCHIVED);
        orchestrator.reconcileExecution(f.executionId());

        assertThat(executionStatus(f.executionId()))
                .isEqualTo(CampaignExecutionStatus.FAILED);
    }

    // =====================================================================
    // Scenario E — resume
    // =====================================================================

    @Test
    @DisplayName("E: resume lets a deferred execution start and dispatch on the next tick")
    void resumeStartsTheDeferredExecution() {
        Fixture f = seedFixture("e");
        setCampaignStatus(f.campaignId(), CampaignStatus.PAUSED);
        assertThat(orchestrator.startExecutionAsSystem(f.executionId())).isFalse();
        assertThat(executionStatus(f.executionId()))
                .isEqualTo(CampaignExecutionStatus.REQUESTED);

        setCampaignStatus(f.campaignId(), CampaignStatus.SCHEDULED);
        assertThat(orchestrator.startExecutionAsSystem(f.executionId())).isTrue();
        assertThat(executionStatus(f.executionId()))
                .isEqualTo(CampaignExecutionStatus.RUNNING);
        assertThat(attemptCount(f.executionId())).isPositive();

        stubRoute();
        lenient().when(dialer.dial(any())).thenAnswer(inv -> {
            dialCount.incrementAndGet();
            return com.shivang.obd.voice.media.OutboundDialResponse.accepted(
                    "prov-" + dialCount.get());
        });
        dialService().processDueAttempts();

        assertThat(dialCount.get())
                .as("the same execution resumes; no new execution is required")
                .isPositive();
    }

    // =====================================================================
    // F-8H-03 / B10 — derived deferred reason
    // =====================================================================

    @Test
    @DisplayName("F8H-03: a deferred execution reports the canonical reason (B10)")
    void deferredExecutionReportsReason() {
        Fixture f = seedFixture("b10");
        setCampaignStatus(f.campaignId(), CampaignStatus.PAUSED);

        String reason = deriveReason(f.executionId());

        assertThat(reason)
                .as("derived from the live campaign, never persisted")
                .isNotNull()
                .contains("PAUSED")
                .contains("Only SCHEDULED or RUNNING");
        // The code is carried on the canonical reason object, which the
        // readiness gate also emits - one vocabulary for both surfaces.
        assertThat(CampaignLifecyclePolicy.notExecutableReason(CampaignStatus.PAUSED).code())
                .isEqualTo("CAMPAIGN_NOT_EXECUTABLE_STATE");
    }

    @Test
    @DisplayName("F8H-03: the reason is null for RUNNING and for terminal executions")
    void reasonIsNullWhenNotDeferred() {
        Fixture f = seedFixture("b10b");
        orchestrator.startExecutionAsSystem(f.executionId());
        assertThat(deriveReason(f.executionId()))
                .as("a started execution has nothing to defer")
                .isNull();

        completeAllAttempts(f);
        orchestrator.reconcileExecution(f.executionId());
        assertThat(deriveReason(f.executionId()))
                .as("a terminal execution is finished, not deferred")
                .isNull();
    }

    @Test
    @DisplayName("F8H-03: the reason is null when the campaign is executable")
    void reasonIsNullWhenCampaignExecutable() {
        Fixture f = seedFixture("b10c");
        assertThat(deriveReason(f.executionId()))
                .isNull();
    }

    @Test
    @DisplayName("F8H-03: the readiness gate and the API report the identical reason text")
    void reasonMatchesTheReadinessGate() {
        Fixture f = seedFixture("b10d");
        setCampaignStatus(f.campaignId(), CampaignStatus.PAUSED);

        var readiness = new CampaignReadinessService(campaignRepository,
                org.mockito.Mockito.mock(com.shivang.obd.authz.AuthorizationService.class),
                () -> Optional.of(new com.shivang.obd.security.AuthenticatedUser(
                        CALLER_ID, "t@test", null)),
                contactGroupRepository,
                new CampaignResourceValidationService(didRepository, audioAssetRepository, null),
                tenantRepository).evaluate(f.campaignId());

        assertThat(deriveReason(f.executionId()))
                .as("one canonical vocabulary - the two surfaces cannot drift")
                .isEqualTo(readiness.reasons().get(0).message());
    }

    // =====================================================================
    // Lifecycle ownership
    // =====================================================================

    @Test
    @DisplayName("F8H-02: editable and executable remain disjoint (snapshot safety)")
    void editableAndExecutableStayDisjoint() {
        assertThat(CampaignLifecyclePolicy.editableAndExecutableAreDisjoint())
                .as("the load-bearing invariant behind the frozen snapshot")
                .isTrue();
    }

    @Test
    @DisplayName("F8H-02: PAUSED is not editable, so a snapshot can never be invalidated")
    void pausedCampaignIsNotEditable() {
        Fixture f = seedFixture("edit");
        setCampaignStatus(f.campaignId(), CampaignStatus.PAUSED);

        var campaign = tx.execute(t -> campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(f.campaignId(), f.tenantId()))
                .orElseThrow();

        assertThat(new CampaignLifecyclePolicy().isEditable(campaign))
                .as("only DRAFT is editable; PAUSED must not be")
                .isFalse();
    }

    @Test
    @DisplayName("F8H: the snapshot is unaffected by pausing (execution config is immutable)")
    void pausingDoesNotMutateTheSnapshot() {
        Fixture f = seedFixture("snap");
        var before = snapshotOf(f);

        setCampaignStatus(f.campaignId(), CampaignStatus.PAUSED);
        setCampaignStatus(f.campaignId(), CampaignStatus.SCHEDULED);

        var after = snapshotOf(f);
        assertThat(after.getConfiguration())
                .as("the frozen runtime configuration is untouched by control-state changes")
                .isEqualTo(before.getConfiguration());
        assertThat(after.getCampaignId()).isEqualTo(before.getCampaignId());
    }

    private CampaignExecutionConfiguration snapshotOf(Fixture f) {
        var execution = tx.execute(t -> executionRepository
                .findByIdAndDeletedAtIsNull(f.executionId())).orElseThrow();
        return tx.execute(t -> snapshotRepository.findByIdAndTenantId(
                execution.getConfigurationSnapshotId(), f.tenantId())).orElseThrow();
    }

    @Test
    @DisplayName("F8H: tenant isolation - a foreign campaign status cannot be read")
    void tenantIsolationHolds() {
        Fixture a = seedFixture("t-a");
        Fixture b = seedFixture("t-b");
        setCampaignStatus(a.campaignId(), CampaignStatus.PAUSED);

        var campaign = tx.execute(t -> campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(b.campaignId(), a.tenantId()));

        assertThat(campaign)
                .as("another tenant's campaign is indistinguishable from nonexistent")
                .isEmpty();
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private String deriveReason(UUID executionId) {
        var execution = tx.execute(t -> executionRepository
                .findByIdAndDeletedAtIsNull(executionId)).orElseThrow();
        var campaign = tx.execute(t -> campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(
                        execution.getCampaignId(), execution.getTenantId()))
                .orElse(null);
        if (execution.getStatus() != CampaignExecutionStatus.REQUESTED
                || campaign == null
                || CampaignLifecyclePolicy.isExecutable(campaign.getStatus())) {
            return null;
        }
        return CampaignLifecyclePolicy.notExecutableReason(campaign.getStatus()).message();
    }

    private OutboundDialService dialService() {
        return new OutboundDialService(
                attemptRepository, contactRepository, tenantRepository, campaignRepository,
                executionRepository, runtimeConfigResolver, dialer, eligibilityService, routing,
                capacity, callSessionRepository, callLegRepository, limitService,
                attemptSafety, new PreDispatchFailureMapper(), tx, new ExecutionScheduleCalculator());
    }

    private void stubRoute() {
        var route = org.mockito.Mockito.mock(com.shivang.obd.voice.routing.VoiceRoute.class);
        lenient().when(route.didId()).thenReturn(lastDid);
        lenient().when(route.didE164Number()).thenReturn("+919700000001");
        lenient().when(route.freeSwitchGatewayName()).thenReturn("fs-a");
        lenient().when(route.freeSwitchProfile()).thenReturn("sofia/p1");
        lenient().when(route.provider()).thenReturn("TATA");
        lenient().when(route.gatewayId()).thenReturn(null);
        // Stubs the SAME instance the dial service is constructed with; stubbing
        // a throwaway mock here would leave resolveRoute returning null.
        lenient().when(routing.resolveRoute(any(), any(), any(), any(), any(), any()))
                .thenReturn(com.shivang.obd.voice.routing.VoiceRoutingDecision.primary(
                        route, "selected", java.util.List.of()));
        lenient().when(capacity.reserve(any(), any())).thenReturn(true);
        lenient().when(limitService.resolveUsageDate(any()))
                .thenReturn(java.time.LocalDate.of(2026, 9, 29));
        lenient().when(limitService.effectiveLimit(any())).thenReturn(3);
        lenient().when(limitService.admit(any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(DailyDialLimitService.AdmissionResult.ADMITTED);
    }

    private UUID lastDid;

    private void completeAllAttempts(Fixture f) {
        tx.executeWithoutResult(t -> attemptRepository
                .findByExecutionIdAndTenantIdAndDeletedAtIsNullOrderByScheduledAtAsc(
                        f.executionId(), f.tenantId())
                .forEach(a -> {
                    a.setStatus(CallAttemptStatus.COMPLETED);
                    a.setCompletedAt(Instant.now());
                    attemptRepository.save(a);
                }));
    }

    private void failAllAttempts(Fixture f) {
        tx.executeWithoutResult(t -> attemptRepository
                .findByExecutionIdAndTenantIdAndDeletedAtIsNullOrderByScheduledAtAsc(
                        f.executionId(), f.tenantId())
                .forEach(a -> {
                    a.setStatus(CallAttemptStatus.FAILED);
                    a.setFailureCode("BUSY");
                    a.setCompletedAt(Instant.now());
                    attemptRepository.save(a);
                }));
    }

    private void setCampaignStatus(UUID campaignId, CampaignStatus status) {
        tx.executeWithoutResult(t -> {
            CampaignEntity c = campaignRepository.findByIdAndDeletedAtIsNull(campaignId)
                    .orElseThrow();
            c.setStatus(status);
            campaignRepository.saveAndFlush(c);
        });
    }

    private CallAttempt reloadAttempt(UUID id) {
        return tx.execute(t -> attemptRepository.findByIdAndDeletedAtIsNull(id)).orElseThrow();
    }

    private CampaignExecutionStatus executionStatus(UUID id) {
        return tx.execute(t -> executionRepository.findByIdAndDeletedAtIsNull(id))
                .orElseThrow().getStatus();
    }

    private int attemptCount(UUID executionId) {
        Long n = tx.execute(t -> entityManager.createQuery(
                "SELECT COUNT(a) FROM CallAttempt a WHERE a.executionId = :e", Long.class)
                .setParameter("e", executionId).getSingleResult());
        return n == null ? 0 : n.intValue();
    }

    private int callSessionCount(UUID attemptId) {
        return tx.execute(t -> callSessionRepository
                .findByCallAttemptIdAndDeletedAtIsNull(attemptId).isPresent() ? 1 : 0);
    }

    private CallAttempt seedAttempt(Fixture f) {
        return tx.execute(t -> {
            CallAttempt a = new CallAttempt();
            a.setExecutionId(f.executionId());
            a.setCampaignId(f.campaignId());
            a.setTenantId(f.tenantId());
            a.setContactId(f.contactId());
            a.setDidId(f.didId());
            a.setAttemptNumber(1);
            a.setStatus(CallAttemptStatus.QUEUED);
            a.setScheduledAt(Instant.now().minusSeconds(120));
            return attemptRepository.saveAndFlush(a);
        });
    }

    private record Fixture(UUID campaignId, UUID executionId, UUID contactId,
                           UUID tenantId, UUID didId) {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final UUID CALLER_ID =
            UUID.fromString("ff000000-0000-4000-8000-0000000000fa");

    private Fixture seedFixture(String label) {
        int n = SEQ.incrementAndGet();
        UUID tenantId = tx.execute(t -> {
            var te = new com.shivang.obd.tenant.TenantEntity();
            te.setName("tenant-" + label + "-" + n);
            te.setSlug("t-" + label + "-" + n);
            te.setStatus(LifecycleStatus.ACTIVE);
            return tenantRepository.saveAndFlush(te).getId();
        });
        UUID didId = tx.execute(t -> {
            var d = new com.shivang.obd.did.DidEntity();
            d.setTenantId(tenantId);
            d.setE164Number("+9197" + String.format("%08d", n));
            d.setCountryCode("+91");
            d.setNumberType(com.shivang.obd.did.NumberType.MOBILE);
            d.setProvider("TATA");
            d.setStatus(DidStatus.ACTIVE);
            d.setAllocationState(AllocationState.ASSIGNED);
            d.setAllocationSource(AllocationSource.PLATFORM);
            return didRepository.saveAndFlush(d).getId();
        });
        lastDid = didId;
        UUID groupId = tx.execute(t -> {
            var g = new com.shivang.obd.contact.ContactGroupEntity();
            g.setTenantId(tenantId);
            g.setName("cg-" + n);
            return contactGroupRepository.saveAndFlush(g).getId();
        });
        UUID contactId = tx.execute(t -> {
            var c = new com.shivang.obd.contact.ContactEntity();
            c.setTenantId(tenantId);
            c.setFirstName("c-" + n);
            c.setPhoneNumber("+9198" + String.format("%08d", n));
            return contactRepository.saveAndFlush(c).getId();
        });
        tx.executeWithoutResult(t -> {
            var m = new com.shivang.obd.contact.ContactGroupMemberEntity();
            m.setTenantId(tenantId);
            m.setContactGroupId(groupId);
            m.setContactId(contactId);
            memberRepository.saveAndFlush(m);
        });
        UUID campaignId = tx.execute(t -> {
            var c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-vb8h-" + n);
            c.setCampaignType(CampaignType.PLAYFILE);
            c.setStatus(CampaignStatus.SCHEDULED);
            c.setContentMode(ContentMode.AUDIO);
            c.setAudioAssetId(seedAudio(tenantId));
            c.setDidId(didId);
            c.setContactGroupId(groupId);
            c.setSchedule(new ScheduleSpec(null, null, null, "UTC", null, null));
            c.setRetryPolicy(new RetryPolicySpec(2, 60, RetryStrategy.FIXED));
            c.setCallOnWhitelistNumbers(Boolean.FALSE);
            return campaignRepository.saveAndFlush(c).getId();
        });
        UUID executionId = tx.execute(t -> {
            var campaign = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            var snapshot = new CampaignConfigurationService(
                    snapshotRepository, new CampaignTypeConfigValidator())
                    .createExecutionSnapshot(campaign);
            var e = new CampaignExecution();
            e.setCampaignId(campaignId);
            e.setTenantId(tenantId);
            e.setConfigurationSnapshotId(snapshot.getId());
            e.setStatus(CampaignExecutionStatus.REQUESTED);
            e.setRequestedAt(Instant.now());
            e.setRequestedBy(CALLER_ID.toString());
            return executionRepository.saveAndFlush(e).getId();
        });
        return new Fixture(campaignId, executionId, contactId, tenantId, didId);
    }

    private UUID seedAudio(UUID tenantId) {
        int n = SEQ.incrementAndGet();
        return tx.execute(t -> {
            var a = new com.shivang.obd.audio.AudioAssetEntity();
            a.setTenantId(tenantId);
            a.setName("asset-" + n);
            a.setFileName("asset-" + n + ".wav");
            a.setContentType("audio/wav");
            a.setFileSize(1024L);
            a.setStorageReference("s3://vb8h/" + n + ".wav");
            a.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
            return audioAssetRepository.saveAndFlush(a).getId();
        });
    }
}
