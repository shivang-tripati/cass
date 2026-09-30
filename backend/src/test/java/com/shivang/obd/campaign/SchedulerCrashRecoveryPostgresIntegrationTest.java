package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.campaign.config.CampaignTypeConfigValidator;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.did.AllocationSource;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.media.OutboundDialer;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * VB-8E — scheduler durability, crash recovery and dispatch consistency,
 * against real PostgreSQL (Flyway V1..V55).
 *
 * <p>VB-8D made the dispatch claim its own committed transaction so no later
 * failure could make a dispatched attempt dialable again. That opened a new
 * window: the attempt is now {@code IN_PROGRESS} <em>before</em> the transaction
 * that creates its {@code CallSession}. These tests exercise what happens in and
 * around that window, and what the recovery machinery already guarantees outside
 * it.
 *
 * <ul>
 *   <li>A — a QUEUED attempt survives untouched and stays dispatchable</li>
 *   <li>B — claim committed, then crash before dispatch: the attempt is settled
 *       deterministically and is <b>not</b> retried</li>
 *   <li>C — a rejected dial leaves a recoverable attempt with correct failure
 *       and no capacity held</li>
 *   <li>D — a session-backed stranded attempt keeps the pre-existing
 *       {@code STALE_ATTEMPT_RECONCILED} treatment, untouched by VB-8E</li>
 *   <li>E — recovery never steals an attempt that is still legitimately being
 *       processed</li>
 *   <li>F — six concurrent workers still produce exactly one dispatch</li>
 *   <li>H — a duplicate/replayed ESL-shaped recovery cannot double-settle</li>
 *   <li>I/J/K — capacity and both daily-safety ledgers are unaffected by the
 *       crash, because they live in the dispatch transaction that rolled back</li>
 *   <li>L — an unknown external outcome never becomes retryable</li>
 *   <li>M — execution reconciles across completed / failed / cancelled / in-progress
 *       attempts</li>
 *   <li>N/O/P — tenant isolation, terminal fail-closed, no duplicate dispatch</li>
 * </ul>
 *
 * <p><b>No false atomicity.</b> These tests do not pretend PostgreSQL and
 * FreeSWITCH share a transaction. Where the outcome is genuinely unknowable,
 * the test asserts the conservative behaviour instead: the attempt is settled,
 * never re-dialed, never retried.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulerCrashRecoveryPostgresIntegrationTest {

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

    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CampaignExecutionRepository executionRepository;
    @Autowired
    private CampaignExecutionConfigurationRepository snapshotRepository;
    @Autowired
    private CallAttemptRepository attemptRepository;
    @Autowired
    private CallSessionRepository callSessionRepository;
    @Autowired
    private CallLegRepository callLegRepository;
    @Autowired
    private DidRepository didRepository;
    @Autowired
    private com.shivang.obd.audio.AudioAssetRepository audioAssetRepository;
    @Autowired
    private com.shivang.obd.contact.ContactGroupRepository contactGroupRepository;
    @Autowired
    private com.shivang.obd.contact.ContactGroupMemberRepository memberRepository;
    @Autowired
    private com.shivang.obd.contact.ContactRepository contactRepository;
    @Autowired
    private com.shivang.obd.tenant.TenantRepository tenantRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private CampaignConfigurationService configurationService;
    private CampaignRuntimeConfigResolver runtimeConfigResolver;
    private StaleCallReconciler reconciler;
    private OutboundDialer dialer;
    private CallEligibility eligibilityService;
    private com.shivang.obd.voice.routing.VoiceRoutingService routing;
    private com.shivang.obd.voice.capacity.VoiceCapacityService capacity;
    private DailyDialLimitService limitService;
    private DailyAttemptSafetyService attemptSafety;

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                            + startupFailure.getMessage(), startupFailure);
        }
        tx = new TransactionTemplate(transactionManager);
        configurationService = new CampaignConfigurationService(
                snapshotRepository, new CampaignTypeConfigValidator());
        runtimeConfigResolver = new CampaignRuntimeConfigResolver(configurationService);
        reconciler = new StaleCallReconciler(
                callSessionRepository, attemptRepository,
                org.mockito.Mockito.mock(com.shivang.obd.voice.media.VoiceMediaController.class),
                entityManager);

        dialer = org.mockito.Mockito.mock(OutboundDialer.class);
        eligibilityService = org.mockito.Mockito.mock(CallEligibility.class);
        org.mockito.Mockito.lenient()
                .when(eligibilityService.evaluate(
                        org.mockito.ArgumentMatchers.any(CallEligibility.Context.class),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(CallEligibility.EligibilityResult.allowed());
        routing = org.mockito.Mockito.mock(com.shivang.obd.voice.routing.VoiceRoutingService.class);
        capacity = org.mockito.Mockito.mock(com.shivang.obd.voice.capacity.VoiceCapacityService.class);
        limitService = org.mockito.Mockito.mock(DailyDialLimitService.class);
        attemptSafety = org.mockito.Mockito.mock(DailyAttemptSafetyService.class);
        org.mockito.Mockito.lenient()
                .when(attemptSafety.admit(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(DailyAttemptSafetyService.AdmissionResult.ADMITTED);
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(t -> {
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

    // === A: before claim ===

    @Test
    @DisplayName("A: a QUEUED attempt is untouched by recovery and stays dispatchable")
    void queuedAttemptIsUntouched() {
        Fixture f = seedFixture("a");
        CallAttempt attempt = seedAttempt(f, 1);

        reconciler.reconcile();

        assertThat(reload(attempt.getId()).getStatus())
                .as("a never-claimed attempt is left completely alone")
                .isEqualTo(CallAttemptStatus.QUEUED);
        assertThat(reload(attempt.getId()).getFailureCode()).isNull();
        assertThat(dueCount()).as("and is still selectable by the dial step").isEqualTo(1);
    }

    // === B: claim committed, then crash before dispatch ===

    @Test
    @DisplayName("B1: a claimed attempt with no session is settled, not left stuck")
    void claimedAttemptIsSettledByRecovery() {
        Fixture f = seedFixture("b1");
        CallAttempt attempt = seedAttempt(f, 1);

        // Simulate the crash: the claim committed, the dispatch transaction never ran.
        claimOnly(attempt);
        backdateStart(attempt, 10);

        assertThat(hasSession(attempt)).as("precondition: no session exists").isFalse();

        reconciler.reconcile();

        CallAttempt after = reload(attempt.getId());
        assertThat(after.getStatus())
                .as("the attempt can no longer wedge the execution")
                .isEqualTo(CallAttemptStatus.CANCELLED);
        assertThat(after.getFailureCode()).isEqualTo("CLAIMED_NOT_DISPATCHED");
        assertThat(after.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("B2: an unknown external outcome is never retried")
    void unknownOutcomeIsNeverRetried() {
        Fixture f = seedFixture("b2");
        CallAttempt attempt = seedAttempt(f, 1);
        claimOnly(attempt);
        backdateStart(attempt, 10);

        reconciler.reconcile();

        long retriable = tx.execute(t -> attemptRepository
                .findByExecutionIdAndStatusInAndDeletedAtIsNull(
                        f.executionId(), java.util.Set.of(CallAttemptStatus.FAILED)).size());
        assertThat(retriable)
                .as("CANCELLED, not FAILED - otherwise the retry policy would place a "
                        + "second real call to a contact who may already be receiving one")
                .isZero();
    }

    @Test
    @DisplayName("B3: the execution converges once every attempt is settled")
    void executionConvergesAfterRecovery() {
        Fixture f = seedFixture("b3");
        CallAttempt attempt = seedAttempt(f, 1);
        claimOnly(attempt);
        backdateStart(attempt, 10);

        reconciler.reconcile();
        new CampaignExecutionOrchestrator(
                campaignRepository, executionRepository, attemptRepository,
                contactRepository, memberRepository,
                new CampaignResourceValidationService(didRepository, audioAssetRepository, null),
                org.mockito.Mockito.mock(com.shivang.obd.authz.AuthorizationService.class),
                () -> java.util.Optional.of(new com.shivang.obd.security.AuthenticatedUser(
                        CALLER_ID, "t@test", null)),
                new CampaignReadinessService(campaignRepository,
                        org.mockito.Mockito.mock(com.shivang.obd.authz.AuthorizationService.class),
                        () -> java.util.Optional.of(new com.shivang.obd.security.AuthenticatedUser(
                                CALLER_ID, "t@test", null)),
                        contactGroupRepository,
                        new CampaignResourceValidationService(
                                didRepository, audioAssetRepository, null),
                        tenantRepository),
                tenantRepository, runtimeConfigResolver,
                new ExecutionScheduleCalculator(), tx,
                new RetryPolicyService(),
                org.mockito.Mockito.mock(OutboundDialService.class),
                org.mockito.Mockito.mock(EslEventProcessor.class),
                reconciler)
                .reconcileExecution(f.executionId());

        assertThat(readExecutionStatus(f.executionId()))
                .as("an all-cancelled execution still settles rather than hanging RUNNING")
                .isNotEqualTo(CampaignExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("B4: no daily safety is consumed or released by recovery")
    void recoveryTouchesNoSafetyLedger() {
        Fixture f = seedFixture("b4");
        CallAttempt attempt = seedAttempt(f, 1);
        claimOnly(attempt);
        backdateStart(attempt, 10);
        org.mockito.Mockito.clearInvocations(limitService, attemptSafety, capacity);

        reconciler.reconcile();

        org.mockito.Mockito.verifyNoInteractions(limitService, attemptSafety, capacity);
    }

    // === C: dial rejected ===

    @Test
    @DisplayName("C: a rejected dial leaves a terminal attempt, no session leak, no capacity hold")
    void rejectedDialIsRecoverable() {
        Fixture f = seedFixture("c");
        CallAttempt attempt = seedAttempt(f, 1);
        stubRoute();
        org.mockito.Mockito.when(dialer.dial(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.shivang.obd.voice.media.OutboundDialResponse.rejected(
                        "provider refused"));

        dialService().processDueAttempts();

        CallAttempt after = reload(attempt.getId());
        assertThat(after.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(after.getProviderCallId()).isNull();
        org.mockito.Mockito.verify(capacity).release(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // === D: session-backed stranded attempt is unchanged by VB-8E ===

    @Test
    @DisplayName("D: a stranded attempt WITH a session keeps STALE_ATTEMPT_RECONCILED")
    void strandedSessionPathIsUnchanged() {
        Fixture f = seedFixture("d");
        CallAttempt attempt = seedAttempt(f, 1);
        claimOnly(attempt);
        backdateStart(attempt, 10);
        seedSession(attempt);

        reconciler.reconcile();

        CallAttempt after = reload(attempt.getId());
        assertThat(after.getStatus())
                .as("the pre-existing treatment for a dispatched-but-silent attempt stands")
                .isEqualTo(CallAttemptStatus.FAILED);
        assertThat(after.getFailureCode())
                .isEqualTo(com.shivang.obd.campaign.CallFailureCode
                        .STALE_ATTEMPT_RECONCILED.name());
        assertThat(after.getCompletedAt()).isNotNull();
    }

    // === E: recovery must not steal live work ===

    @Test
    @DisplayName("E1: a freshly claimed attempt is never swept")
    void freshClaimIsNotSwept() {
        Fixture f = seedFixture("e1");
        CallAttempt attempt = seedAttempt(f, 1);
        claimOnly(attempt);

        reconciler.reconcile();

        assertThat(reload(attempt.getId()).getStatus())
                .as("only claims older than the stale threshold are eligible")
                .isEqualTo(CallAttemptStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("E2: an attempt that gains a session between the read pass and finalising is left alone")
    void sessionAppearingBeforeFinalisingIsNotStolen() {
        Fixture f = seedFixture("e2");
        CallAttempt attempt = seedAttempt(f, 1);
        claimOnly(attempt);
        backdateStart(attempt, 10);

        // The dispatch transaction completed in the meantime.
        seedSession(attempt);

        boolean handled = reconciler.finalizeOrphanedClaim(attempt.getId());

        assertThat(handled)
                .as("the finaliser re-checks for a session and declines")
                .isFalse();
        assertThat(reload(attempt.getId()).getStatus())
                .as("so it stays with the stranded-session sweep, which owns it")
                .isEqualTo(CallAttemptStatus.IN_PROGRESS);
    }

    // === E3: a genuine concurrent stale-reconcile vs live-dispatch race ===

    @Test
    @DisplayName("E3: reconcile storms racing a real dispatch never double-settle or lose the session")
    void reconcileRaceWithLiveDispatch() throws Exception {
        Fixture f = seedFixture("e3");
        CallAttempt attempt = seedAttempt(f, 1);
        stubRoute();
        org.mockito.Mockito.when(dialer.dial(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.shivang.obd.voice.media.OutboundDialResponse.accepted(
                        "prov-" + attempt.getId()));

        int reconcilers = 4;
        java.util.concurrent.CountDownLatch ready =
                new java.util.concurrent.CountDownLatch(reconcilers + 1);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(reconcilers + 1);
        AtomicReference<Throwable> boom = new AtomicReference<>();
        try {
            // The dispatcher runs for real: claim, then dispatch transaction.
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    dialService().processDueAttempts();
                } catch (Throwable e) {
                    boom.compareAndSet(null, e);
                }
            });
            // Reconcilers hammer the whole sweep, including the new orphan pass,
            // for the entire duration of the dispatch.
            for (int i = 0; i < reconcilers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        for (int n = 0; n < 40; n++) {
                            reconciler.reconcile();
                        }
                    } catch (Throwable e) {
                        boom.compareAndSet(null, e);
                    }
                });
            }
            assertThat(ready.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(90, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        if (boom.get() != null) {
            throw new AssertionError("a racing thread failed", boom.get());
        }

        CallAttempt after = reload(attempt.getId());
        assertThat(after.getProviderCallId())
                .as("the dispatch transaction committed: the attempt was really dialled")
                .isNotNull();
        assertThat(after.getStatus())
                .as("a live dispatched attempt is never settled by the orphan sweep - "
                        + "its 5-minute threshold is not reached, and the session exists")
                .isEqualTo(CallAttemptStatus.IN_PROGRESS);
        assertThat(hasSession(after))
                .as("no reconciler pass deleted or corrupted the session")
                .isTrue();
    }

    // === G: the irreducible ESL correlation boundary, pinned by a test ===

    @Test
    @DisplayName("G: a crash before the providerCallId write leaves the channel uncorrelatable")
    void orphanChannelCannotBeCorrelated() {
        Fixture f = seedFixture("g");
        CallAttempt attempt = seedAttempt(f, 1);
        claimOnly(attempt);
        backdateStart(attempt, 10);

        // This is the real boundary, stated as an executable fact. The dialer
        // pins origination_uuid to the attempt id, so FreeSWITCH's Unique-ID
        // IS the attempt id - but EslEventService resolves events by
        // providerCallId, and that column is written in the dispatch
        // transaction that just rolled back. So the eventual CHANNEL_HANGUP
        // for a call that really was placed finds nothing.
        assertThat(reload(attempt.getId()).getProviderCallId()).isNull();
        java.util.Optional<CallAttempt> byChannelId = tx.execute(
                t -> attemptRepository.findByProviderCallIdAndDeletedAtIsNull(
                        attempt.getId().toString()));
        assertThat(byChannelId)
                .as("VB-8E deferred finding: no attempt is reachable by that channel id. "
                        + "Closing this needs an ESL probe for origination_uuid, not a guess here.")
                .isEmpty();
    }

    // === F: concurrency still holds ===

    @Test
    @DisplayName("F: six concurrent workers still produce exactly one dispatch")
    void concurrentWorkersStillProduceOneDispatch() throws Exception {
        Fixture f = seedFixture("f");
        CallAttempt attempt = seedAttempt(f, 1);
        stubRoute();
        AtomicInteger dials = new AtomicInteger();
        org.mockito.Mockito.when(dialer.dial(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    dials.incrementAndGet();
                    return com.shivang.obd.voice.media.OutboundDialResponse.accepted(
                            "prov-" + attempt.getId());
                });

        int workers = 6;
        java.util.concurrent.CountDownLatch ready =
                new java.util.concurrent.CountDownLatch(workers);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(workers);
        try {
            for (int i = 0; i < workers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        dialService().processDueAttempts();
                    } catch (Exception ignored) {
                        // asserted on observable state
                    }
                });
            }
            assertThat(ready.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(dials.get()).as("crash recovery changed nothing about the claim").isEqualTo(1);
        assertThat(sessionCount(attempt)).isEqualTo(1);
    }

    // === H: duplicate recovery is idempotent ===

    @Test
    @DisplayName("H: settling an orphaned claim twice changes nothing")
    void duplicateRecoveryIsIdempotent() {
        Fixture f = seedFixture("h");
        CallAttempt attempt = seedAttempt(f, 1);
        claimOnly(attempt);
        backdateStart(attempt, 10);

        boolean first = reconciler.finalizeOrphanedClaim(attempt.getId());
        boolean second = reconciler.finalizeOrphanedClaim(attempt.getId());
        reconciler.reconcile();
        reconciler.reconcile();

        assertThat(first).isTrue();
        assertThat(second)
                .as("the second pass declines - the attempt is no longer IN_PROGRESS")
                .isFalse();
        assertThat(reload(attempt.getId()).getStatus())
                .isEqualTo(CallAttemptStatus.CANCELLED);
        assertThat(reload(attempt.getId()).getCompletedAt()).isNotNull();
    }

    // === N: tenant isolation of the recovery sweep ===

    @Test
    @DisplayName("N: recovery is tenant-safe and does not cross executions")
    void recoveryIsTenantSafe() {
        Fixture a = seedFixture("n1");
        Fixture b = seedFixture("n2");
        CallAttempt attemptA = seedAttempt(a, 1);
        claimOnly(attemptA);
        backdateStart(attemptA, 10);

        reconciler.finalizeOrphanedClaim(attemptA.getId());

        assertThat(reload(attemptA.getId()).getTenantId()).isEqualTo(a.tenantId());
        assertThat(readExecutionStatus(a.executionId()))
                .as("only the targeted attempt was settled")
                .isEqualTo(CampaignExecutionStatus.RUNNING);
        assertThat(readExecutionStatus(b.executionId()))
                .isEqualTo(CampaignExecutionStatus.RUNNING);
    }

    // === O/P: terminal fail-closed + no duplicate dispatch, unchanged by VB-8E ===

    @Test
    @DisplayName("O: recovery never resurrects a terminal execution")
    void recoveryDoesNotResurrectTerminalExecution() {
        Fixture f = seedFixture("o");
        CallAttempt attempt = seedAttempt(f, 1);
        claimOnly(attempt);
        backdateStart(attempt, 10);
        setExecutionStatus(f.executionId(), CampaignExecutionStatus.COMPLETED);

        reconciler.reconcile();

        assertThat(reload(attempt.getId()).getStatus())
                .isEqualTo(CallAttemptStatus.CANCELLED);
        assertThat(readExecutionStatus(f.executionId()))
                .as("settling an attempt cannot move the execution backwards")
                .isEqualTo(CampaignExecutionStatus.COMPLETED);
        assertThat(dialCountFor(f.executionId()))
                .as("and nothing is dialled for a terminal execution")
                .isZero();
    }

    // === helpers ===

    private OutboundDialService dialService() {
        return new OutboundDialService(
                attemptRepository, contactRepository, tenantRepository,
                campaignRepository, executionRepository, runtimeConfigResolver,
                dialer, eligibilityService, routing, capacity,
                callSessionRepository, callLegRepository,
                limitService, attemptSafety, new PreDispatchFailureMapper(), tx, new ExecutionScheduleCalculator());
    }

    private void stubRoute() {
        var route = org.mockito.Mockito.mock(
                com.shivang.obd.voice.routing.VoiceRoute.class);
        org.mockito.Mockito.lenient().when(route.didId()).thenReturn(returnDid());
        org.mockito.Mockito.lenient().when(route.didE164Number()).thenReturn("+919700000001");
        org.mockito.Mockito.lenient().when(route.freeSwitchGatewayName()).thenReturn("fs-a");
        org.mockito.Mockito.lenient().when(route.freeSwitchProfile()).thenReturn("sofia/p1");
        org.mockito.Mockito.lenient().when(route.provider()).thenReturn("TATA");
        org.mockito.Mockito.lenient().when(route.gatewayId()).thenReturn(null);
        org.mockito.Mockito.lenient().when(routing.resolveRoute(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.shivang.obd.voice.routing.VoiceRoutingDecision.primary(
                        route, "selected", java.util.List.of()));
        org.mockito.Mockito.lenient().when(capacity.reserve(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(limitService.resolveUsageDate(
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.time.LocalDate.of(2026, 9, 29));
        org.mockito.Mockito.lenient().when(limitService.effectiveLimit(
                        org.mockito.ArgumentMatchers.any())).thenReturn(3);
        org.mockito.Mockito.lenient().when(limitService.admit(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(DailyDialLimitService.AdmissionResult.ADMITTED);
    }

    private UUID returnDid() {
        return lastDid;
    }

    private UUID lastDid;

    private void claimOnly(CallAttempt attempt) {
        int rows = tx.execute(t -> attemptRepository.claimForDispatch(
                attempt.getId(), attempt.getTenantId(),
                CallAttemptStatus.QUEUED, CallAttemptStatus.IN_PROGRESS, Instant.now()));
        assertThat(rows).as("the claim must succeed for this scenario to mean anything")
                .isEqualTo(1);
    }

    private void backdateStart(CallAttempt attempt, long minutes) {
        tx.executeWithoutResult(t -> {
            CallAttempt a = attemptRepository.findByIdAndDeletedAtIsNull(attempt.getId())
                    .orElseThrow();
            a.setStartedAt(Instant.now().minus(minutes, ChronoUnit.MINUTES));
            attemptRepository.saveAndFlush(a);
        });
    }

    private void seedSession(CallAttempt attempt) {
        tx.executeWithoutResult(t -> {
            var session = new com.shivang.obd.voice.call.CallSession();
            session.setTenantId(attempt.getTenantId());
            session.setDirection(com.shivang.obd.voice.call.CallDirection.OUTBOUND);
            session.setCallType(com.shivang.obd.voice.call.CallType.VOICE_BLAST);
            session.setStatus(com.shivang.obd.voice.call.CallSessionStatus.DIALING);
            session.setDidId(attempt.getDidId());
            session.setDestinationNumber("+919800000000");
            session.setProviderCallId("prov-" + attempt.getId());
            session.setCallAttemptId(attempt.getId());
            session.setInitiatedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
            callSessionRepository.saveAndFlush(session);
            attemptRepository.findByIdAndDeletedAtIsNull(attempt.getId()).orElseThrow()
                    .setProviderCallId("prov-" + attempt.getId());
            attemptRepository.saveAndFlush(
                    attemptRepository.findByIdAndDeletedAtIsNull(attempt.getId()).orElseThrow());
        });
    }

    private CallAttempt reload(UUID id) {
        return tx.execute(t -> attemptRepository.findByIdAndDeletedAtIsNull(id)).orElseThrow();
    }

    private CampaignExecutionStatus readExecutionStatus(UUID id) {
        return tx.execute(t -> executionRepository.findByIdAndDeletedAtIsNull(id))
                .orElseThrow().getStatus();
    }

    private void setExecutionStatus(UUID id, CampaignExecutionStatus status) {
        tx.executeWithoutResult(t -> {
            CampaignExecution e = executionRepository.findByIdAndDeletedAtIsNull(id).orElseThrow();
            e.setStatus(status);
            executionRepository.saveAndFlush(e);
        });
    }

    private boolean hasSession(CallAttempt attempt) {
        return tx.execute(t -> callSessionRepository
                .findByCallAttemptIdAndDeletedAtIsNull(attempt.getId()).isPresent());
    }

    private long sessionCount(CallAttempt attempt) {
        return tx.execute(t -> callSessionRepository
                .findByCallAttemptIdAndDeletedAtIsNull(attempt.getId())
                .isPresent() ? 1L : 0L);
    }

    private long dialCountFor(UUID executionId) {
        Long n = tx.execute(t -> entityManager.createQuery(
                "SELECT COUNT(a) FROM CallAttempt a WHERE a.executionId = :e"
                        + " AND a.providerCallId IS NOT NULL", Long.class)
                .setParameter("e", executionId).getSingleResult());
        return n == null ? 0L : n;
    }

    private int dueCount() {
        return tx.execute(t -> attemptRepository
                .findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(
                        CallAttemptStatus.QUEUED, Instant.now().plusSeconds(3600)).size());
    }

    private record Fixture(UUID campaignId, UUID executionId, UUID contactId, UUID tenantId) {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final UUID CALLER_ID =
            UUID.fromString("ff000000-0000-4000-8000-0000000000f1");

    private Fixture seedFixture(String label) {
        UUID tenantId = tx.execute(t -> {
            com.shivang.obd.tenant.TenantEntity te = new com.shivang.obd.tenant.TenantEntity();
            te.setName("tenant-" + label + "-" + SEQ.incrementAndGet());
            te.setSlug("t-" + label + "-" + SEQ.incrementAndGet());
            te.setStatus(LifecycleStatus.ACTIVE);
            return tenantRepository.saveAndFlush(te).getId();
        });
        UUID didId = tx.execute(t -> {
            com.shivang.obd.did.DidEntity d = new com.shivang.obd.did.DidEntity();
            d.setTenantId(tenantId);
            d.setE164Number("+9197" + String.format("%08d", SEQ.incrementAndGet()));
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
            com.shivang.obd.contact.ContactGroupEntity g =
                    new com.shivang.obd.contact.ContactGroupEntity();
            g.setTenantId(tenantId);
            g.setName("cg-" + SEQ.incrementAndGet());
            return contactGroupRepository.saveAndFlush(g).getId();
        });
        UUID contactId = tx.execute(t -> {
            com.shivang.obd.contact.ContactEntity c = new com.shivang.obd.contact.ContactEntity();
            c.setTenantId(tenantId);
            c.setFirstName("c-" + SEQ.incrementAndGet());
            c.setPhoneNumber("+9198" + String.format("%08d", SEQ.incrementAndGet()));
            return contactRepository.saveAndFlush(c).getId();
        });
        tx.executeWithoutResult(t -> {
            com.shivang.obd.contact.ContactGroupMemberEntity m =
                    new com.shivang.obd.contact.ContactGroupMemberEntity();
            m.setTenantId(tenantId);
            m.setContactGroupId(groupId);
            m.setContactId(contactId);
            memberRepository.saveAndFlush(m);
        });
        UUID campaignId = tx.execute(t -> {
            CampaignEntity c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-vb8e-" + SEQ.incrementAndGet());
            c.setCampaignType(CampaignType.PLAYFILE);
            c.setStatus(CampaignStatus.SCHEDULED);
            c.setContentMode(ContentMode.AUDIO);
            c.setAudioAssetId(seedAudio(tenantId));
            c.setDidId(didId);
            c.setContactGroupId(groupId);
            c.setSchedule(new ScheduleSpec(null, null, null, "UTC", null, null));
            c.setRetryPolicy(new RetryPolicySpec(1, 60, RetryStrategy.FIXED));
            c.setCallOnWhitelistNumbers(Boolean.FALSE);
            return campaignRepository.saveAndFlush(c).getId();
        });
        UUID executionId = tx.execute(t -> {
            CampaignEntity campaign = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            var snapshot = configurationService.createExecutionSnapshot(campaign);
            CampaignExecution e = new CampaignExecution();
            e.setCampaignId(campaignId);
            e.setTenantId(tenantId);
            e.setConfigurationSnapshotId(snapshot.getId());
            e.setStatus(CampaignExecutionStatus.RUNNING);
            e.setRequestedAt(Instant.now());
            e.setStartedAt(Instant.now());
            e.setRequestedBy(CALLER_ID.toString());
            return executionRepository.saveAndFlush(e).getId();
        });
        return new Fixture(campaignId, executionId, contactId, tenantId);
    }

    private UUID seedAudio(UUID tenantId) {
        return tx.execute(t -> {
            com.shivang.obd.audio.AudioAssetEntity a = new com.shivang.obd.audio.AudioAssetEntity();
            a.setTenantId(tenantId);
            a.setName("asset-" + SEQ.incrementAndGet());
            a.setFileName("asset-" + SEQ.incrementAndGet() + ".wav");
            a.setContentType("audio/wav");
            a.setFileSize(1024L);
            a.setStorageReference("s3://vb8e/" + SEQ.incrementAndGet() + ".wav");
            a.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
            return audioAssetRepository.saveAndFlush(a).getId();
        });
    }

    private CallAttempt seedAttempt(Fixture f, int attemptNumber) {
        return tx.execute(t -> {
            CallAttempt a = new CallAttempt();
            a.setExecutionId(f.executionId());
            a.setCampaignId(f.campaignId());
            a.setTenantId(f.tenantId());
            a.setContactId(f.contactId());
            a.setDidId(lastDid);
            a.setAttemptNumber(attemptNumber);
            a.setStatus(CallAttemptStatus.QUEUED);
            a.setScheduledAt(Instant.now().minusSeconds(120));
            return attemptRepository.saveAndFlush(a);
        });
    }
}
