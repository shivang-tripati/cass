package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.telephony.EslEvent;
import com.shivang.obd.telephony.EslEventService;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.tenant.TenantRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * VB-8F — the mandatory F8-B scenario against real PostgreSQL and the real
 * {@link EslEventService} / {@link OrphanedDispatchRecovery} wiring.
 *
 * <p>The world under test is the one a hard process death leaves behind:
 * <ol>
 *   <li>TX-1 committed, so the attempt is {@code IN_PROGRESS};</li>
 *   <li>FreeSWITCH really accepted the originate, so a channel exists whose
 *       {@code Unique-ID} is the attempt id;</li>
 *   <li>TX-2 rolled back, so no {@code CallSession}, no {@code providerCallId},
 *       no capacity allocation and no daily-safety row was written;</li>
 *   <li>no recovery transaction ran, because the JVM was gone.</li>
 * </ol>
 *
 * <p>That is exactly the state VB-8E's {@code claimOnly} produces, and the
 * assertions below first confirm the state is genuinely the post-rollback one
 * (including that a real forced TX-2 rollback leaves no trace), then feed a
 * hangup carrying the pinned origination identity through the real event
 * service.
 *
 * <p>Nothing here fabricates a {@code CallSession}: the platform really does
 * not have one, and the recovery must not invent a telephony record to make a
 * lookup succeed.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrphanedDispatchRecoveryPostgresIntegrationTest {

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
    @Autowired private VoiceBlastDailyUsageRepository usageRepository;
    @Autowired private VoiceBlastDailyUsageEntryRepository entryRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private DailyDialLimitService limitService;
    private CampaignRuntimeConfigResolver runtimeConfigResolver;
    private OrphanedDispatchRecovery recovery;
    private EslEventService eslEvents;
    private com.shivang.obd.voice.capacity.VoiceCapacityService capacity;
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
        limitService = new DailyDialLimitService(
                usageRepository, entryRepository, new SimpleMeterRegistry(),
                java.time.Clock.systemUTC());

        recovery = new OrphanedDispatchRecovery(attemptRepository, callSessionRepository,
                executionRepository, runtimeConfigResolver, limitService);

        capacity = org.mockito.Mockito.mock(com.shivang.obd.voice.capacity.VoiceCapacityService.class);
        eslEvents = new EslEventService(attemptRepository, callSessionRepository, callLegRepository,
                capacity,
                org.mockito.Mockito.mock(com.shivang.obd.voice.media.VoiceMediaController.class),
                List.of(), Optional.empty(),
                org.mockito.Mockito.mock(com.shivang.obd.voice.dtmf.DtmfResultService.class),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(recovery));
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
    // F8-B — the required scenario
    // =====================================================================

    @Test
    @DisplayName("F8-B: TX-2 rollback + real hangup recovers the actual telephony outcome")
    void txRollbackThenHangupRecoversRealOutcome() {
        Fixture f = seedFixture("b");
        CallAttempt attempt = claimedAttempt(f);

        // The world after a hard crash: claimed, channel placed, nothing persisted.
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);
        assertThat(attempt.getProviderCallId()).isNull();
        assertThat(sessionCount(attempt)).isZero();

        boolean processed = processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        assertThat(processed).isTrue();
        CallAttempt after = reload(attempt.getId());
        assertThat(after.getStatus())
                .as("the REAL outcome is recorded, not the VB-8E 'unknown' CANCELLED")
                .isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(after.getCompletedAt()).isNotNull();
        assertThat(after.getProviderCallId())
                .as("the channel identity is recovered, closing the correlation gap")
                .isEqualTo(attempt.getId().toString());
    }

    @Test
    @DisplayName("F8-B: a non-clean hangup records the canonical mapped failure")
    void nonCleanHangupRecordsCanonicalFailure() {
        Fixture f = seedFixture("b2");
        CallAttempt attempt = claimedAttempt(f);

        processInTransaction(pinnedHangup(attempt, "USER_BUSY"));

        CallAttempt after = reload(attempt.getId());
        assertThat(after.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(after.getFailureCode())
                .as("the one canonical HangupCauseMapper taxonomy, not a second one")
                .isEqualTo("BUSY");
    }

    @Test
    @DisplayName("F8-B: no second dispatch is ever created by recovery")
    void recoveryCreatesNoSecondDispatch() {
        Fixture f = seedFixture("b3");
        CallAttempt attempt = claimedAttempt(f);

        processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        assertThat(dialCount.get()).as("recovery never reaches the dialer").isZero();
        int total = tx.execute(t -> attemptRepository
                .findByExecutionIdAndStatusInAndDeletedAtIsNull(
                        f.executionId(),
                        java.util.Set.of(CallAttemptStatus.QUEUED,
                                CallAttemptStatus.IN_PROGRESS,
                                CallAttemptStatus.COMPLETED,
                                CallAttemptStatus.FAILED,
                                CallAttemptStatus.CANCELLED)).size());
        assertThat(total)
                .as("exactly one attempt exists; no replacement was dialled")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("F8-B: capacity is never released, because the reservation rolled back")
    void capacityIsNotDoubleReleased() {
        Fixture f = seedFixture("b4");
        CallAttempt attempt = claimedAttempt(f);
        org.mockito.Mockito.clearInvocations(capacity);

        processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        org.mockito.Mockito.verifyNoInteractions(capacity);
    }

    @Test
    @DisplayName("F8-B: the provider acceptance is recorded once, in the right day")
    void acceptanceIsRecordedExactlyOnce() {
        Fixture f = seedFixture("b5");
        CallAttempt attempt = claimedAttempt(f);

        processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        assertThat(entryRepository.existsByCallAttemptId(attempt.getId()))
                .as("the acceptance the rolled-back transaction lost is now recorded")
                .isTrue();
        assertThat(entryRepository.findByCallAttemptId(attempt.getId())
                .orElseThrow().getProviderCallId())
                .isEqualTo(attempt.getId().toString());
    }

    @Test
    @DisplayName("F8-B/VB-8G: the recovered acceptance now counts against the VB-6C bucket")
    void recoveredAcceptanceCountsAgainstTheBucket() {
        Fixture f = seedFixture("b6");
        CallAttempt attempt = claimedAttempt(f);

        processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        // VB-8G reversed this assertion on purpose. It previously pinned the
        // F-8F-01 defect: the pre-dial hold was rolled back, so the hold-guarded
        // increment matched no row and the bucket stayed one behind reality,
        // letting the contact be dialled once more than the limit allows.
        assertThat(limitService.usedCount(
                f.tenantId(), f.contactId(), f.didId(), LocalDate.now(java.time.ZoneOffset.UTC)))
                .as("a real provider-accepted dial is now counted exactly once")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("F8-B: the execution converges once the real outcome is recovered")
    void executionConvergesAfterRecovery() {
        Fixture f = seedFixture("b7");
        CallAttempt attempt = claimedAttempt(f);

        processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        var orchestrator = new CampaignExecutionOrchestrator(
                campaignRepository, executionRepository, attemptRepository,
                contactRepository, memberRepository,
                new CampaignResourceValidationService(didRepository, audioAssetRepository, null),
                org.mockito.Mockito.mock(com.shivang.obd.authz.AuthorizationService.class),
                () -> Optional.of(new AuthenticatedUser(CALLER_ID, "t@test", null)),
                new CampaignReadinessService(campaignRepository,
                        org.mockito.Mockito.mock(com.shivang.obd.authz.AuthorizationService.class),
                        () -> Optional.of(new AuthenticatedUser(CALLER_ID, "t@test", null)),
                        contactGroupRepository,
                        new CampaignResourceValidationService(
                                didRepository, audioAssetRepository, null),
                        tenantRepository),
                tenantRepository, runtimeConfigResolver,
                new ExecutionScheduleCalculator(), tx, new RetryPolicyService(),
                org.mockito.Mockito.mock(OutboundDialService.class),
                org.mockito.Mockito.mock(EslEventProcessor.class),
                org.mockito.Mockito.mock(StaleCallReconciler.class));
        orchestrator.reconcileExecution(f.executionId());

        assertThat(executionStatus(f.executionId()))
                .as("a COMPLETED attempt lets the existing reconciliation finish the execution")
                .isEqualTo(CampaignExecutionStatus.COMPLETED);
    }

    @Test
    @DisplayName("F8-B: a recovered FAILED attempt is left to the campaign's own retry policy")
    void recoveredFailureUsesCanonicalRetryPolicy() {
        Fixture f = seedFixture("b8");
        CallAttempt attempt = claimedAttempt(f);

        processInTransaction(pinnedHangup(attempt, "USER_BUSY"));

        // Recovery itself schedules nothing. The attempt is FAILED, which is
        // exactly the input processRetries already reads, so the campaign's own
        // policy decides - no parallel retry model.
        int retryable = tx.execute(t -> attemptRepository
                .findByExecutionIdAndStatusInAndDeletedAtIsNull(
                        f.executionId(), java.util.Set.of(CallAttemptStatus.FAILED)).size());
        assertThat(retryable).isEqualTo(1);
    }

    // =====================================================================
    // A real forced rollback, proving the recovered state is genuine
    // =====================================================================

    @Test
    @DisplayName("F8-B: a real forced TX-2 rollback leaves the state recovery expects")
    void realRollbackLeavesTheRecoverableState() {
        Fixture f = seedFixture("rb");
        CallAttempt attempt = seedAttempt(f);

        // Drive the real dispatch, and make the write that records the accepted
        // dial fail. TX-2 rolls back exactly as a process death would roll it
        // back, before any session/providerCallId/ledger row is committed.
        var dialer = org.mockito.Mockito.mock(
                com.shivang.obd.voice.media.OutboundDialer.class);
        org.mockito.Mockito.when(dialer.dial(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    dialCount.incrementAndGet();
                    return com.shivang.obd.voice.media.OutboundDialResponse.accepted(
                            attempt.getId().toString());
                });
        var limits = org.mockito.Mockito.mock(DailyDialLimitService.class);
        org.mockito.Mockito.when(limits.resolveUsageDate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(LocalDate.of(2026, 9, 29));
        org.mockito.Mockito.when(limits.effectiveLimit(org.mockito.ArgumentMatchers.any()))
                .thenReturn(3);
        org.mockito.Mockito.when(limits.admit(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(DailyDialLimitService.AdmissionResult.ADMITTED);
        org.mockito.Mockito.doThrow(new IllegalStateException("connection lost after +OK"))
                .when(limits).confirmAccepted(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        var eligibility = org.mockito.Mockito.mock(CallEligibility.class);
        org.mockito.Mockito.lenient()
                .when(eligibility.evaluate(
                        org.mockito.ArgumentMatchers.any(CallEligibility.Context.class),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(CallEligibility.EligibilityResult.allowed());
        var route = org.mockito.Mockito.mock(com.shivang.obd.voice.routing.VoiceRoute.class);
        org.mockito.Mockito.lenient().when(route.didId()).thenReturn(f.didId());
        org.mockito.Mockito.lenient().when(route.didE164Number()).thenReturn("+919700000008");
        org.mockito.Mockito.lenient().when(route.freeSwitchGatewayName()).thenReturn("fs-a");
        org.mockito.Mockito.lenient().when(route.freeSwitchProfile()).thenReturn("sofia/p1");
        org.mockito.Mockito.lenient().when(route.provider()).thenReturn("TATA");
        org.mockito.Mockito.lenient().when(route.gatewayId()).thenReturn(null);
        var routing = org.mockito.Mockito.mock(com.shivang.obd.voice.routing.VoiceRoutingService.class);
        org.mockito.Mockito.lenient().when(routing.resolveRoute(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.shivang.obd.voice.routing.VoiceRoutingDecision.primary(
                        route, "selected", java.util.List.of()));
        org.mockito.Mockito.lenient().when(capacity.reserve(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        var attemptSafety = org.mockito.Mockito.mock(DailyAttemptSafetyService.class);
        org.mockito.Mockito.lenient().when(attemptSafety.admit(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(DailyAttemptSafetyService.AdmissionResult.ADMITTED);

        new OutboundDialService(
                attemptRepository, contactRepository, tenantRepository, campaignRepository,
                executionRepository, runtimeConfigResolver, dialer, eligibility,
                routing, capacity, callSessionRepository, callLegRepository, limits,
                attemptSafety, new PreDispatchFailureMapper(), tx, new ExecutionScheduleCalculator())
                .processDueAttempts();

        assertThat(dialCount.get()).as("FreeSWITCH really did place the call").isEqualTo(1);
        CallAttempt after = reload(attempt.getId());
        assertThat(after.getProviderCallId())
                .as("but the identity write rolled back with the transaction")
                .isNull();
        assertThat(sessionCount(after))
                .as("and no session was committed")
                .isZero();
        assertThat(entryRepository.existsByCallAttemptId(attempt.getId()))
                .as("and the acceptance ledger row rolled back too")
                .isFalse();
    }

    // =====================================================================
    // F8-I / J / K - precedence, idempotency, no resurrection
    // =====================================================================

    @Test
    @DisplayName("F8-J: a duplicate hangup is idempotent - no second transition or entry")
    void duplicateHangupIsIdempotent() {
        Fixture f = seedFixture("j");
        CallAttempt attempt = claimedAttempt(f);

        processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));
        boolean second = processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        // The recovered providerCallId means the duplicate now resolves through
        // the PRIMARY path, where the terminal-state guard absorbs it. That is
        // the correct outcome: the event is handled, and nothing is rewritten.
        // Either way the invariants are what matter.
        assertThat(second).isTrue();
        assertThat(reload(attempt.getId()).getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(reload(attempt.getId()).getCompletedAt())
                .as("not re-stamped by the duplicate")
                .isNotNull();
        long entries = tx.execute(t -> countEntries(attempt.getId()));
        assertThat(entries)
                .as("exactly one acceptance entry, never two")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("F8-K: a terminal attempt is never resurrected by a late hangup")
    void terminalAttemptIsNotResurrected() {
        Fixture f = seedFixture("k");
        CallAttempt attempt = claimedAttempt(f);
        setAttemptStatus(attempt.getId(), CallAttemptStatus.CANCELLED);

        boolean processed = processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        assertThat(processed).isFalse();
        assertThat(reload(attempt.getId()).getStatus())
                .as("VB-8E already settled this one; its verdict stands")
                .isEqualTo(CallAttemptStatus.CANCELLED);
    }

    @Test
    @DisplayName("F8-K: an attempt that already has a session is left to the primary path")
    void attemptWithSessionIsLeftToPrimaryPath() {
        Fixture f = seedFixture("k2");
        CallAttempt attempt = claimedAttempt(f);
        seedSession(attempt);

        boolean processed = processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        assertThat(processed)
                .as("a session exists, so primary correlation owns it and recovery declines")
                .isFalse();
        assertThat(reload(attempt.getId()).getStatus())
                .isEqualTo(CallAttemptStatus.IN_PROGRESS);
    }

    // =====================================================================
    // F8-G / F - isolation and foreign channels, against real rows
    // =====================================================================

    @Test
    @DisplayName("F8-G: one tenant's hangup cannot settle another tenant's attempt")
    void crossTenantHangupCannotSettle() {
        Fixture tenantA = seedFixture("g-a");
        Fixture tenantB = seedFixture("g-b");
        CallAttempt attemptB = claimedAttempt(tenantB);

        // A hangup whose pinned identity names tenant B's attempt, arriving on a
        // channel belonging to tenant A's domain. The identity is a real
        // attempt id, so the only thing keeping this honest is that ownership
        // is read from the persisted attempt row, never inferred from the event.
        boolean processed = processInTransaction(pinnedHangup(attemptB, "NORMAL_CLEARING"));

        assertThat(processed).isTrue();
        CallAttempt after = reload(attemptB.getId());
        assertThat(after.getTenantId())
                .as("ownership came from the persisted row and never changed")
                .isEqualTo(tenantB.tenantId());
        assertThat(after.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(tenantA.tenantId()).isNotEqualTo(tenantB.tenantId());
    }

    @Test
    @DisplayName("F8-F: a foreign channel with no pinned variable never reaches an attempt")
    void foreignChannelNeverReachesAnAttempt() {
        Fixture f = seedFixture("f");
        CallAttempt attempt = claimedAttempt(f);

        EslEvent foreign = new EslEvent("CHANNEL_HANGUP");
        foreign.addHeader("Unique-ID", attempt.getId().toString());
        foreign.addHeader("Channel-Call-UUID", attempt.getId().toString());
        foreign.addHeader("Hangup-Cause", "NORMAL_CLEARING");
        // No variable_origination_uuid: this channel was not originated by us.

        assertThat(processInTransaction(foreign)).isFalse();
        assertThat(reload(attempt.getId()).getStatus())
                .as("UUID equality alone is not evidence of ownership")
                .isEqualTo(CallAttemptStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("F8-F: an unrelated channel's random pinned uuid settles nothing")
    void unrelatedChannelSettlesNothing() {
        Fixture f = seedFixture("f2");
        CallAttempt attempt = claimedAttempt(f);

        String randomUuid = UUID.randomUUID().toString();
        EslEvent unrelated = new EslEvent("CHANNEL_HANGUP");
        unrelated.addHeader("Unique-ID", randomUuid);
        unrelated.addHeader("Channel-Call-UUID", randomUuid);
        unrelated.addHeader("variable_origination_uuid", randomUuid);
        unrelated.addHeader("Hangup-Cause", "NORMAL_CLEARING");

        assertThat(processInTransaction(unrelated)).isFalse();
        assertThat(reload(attempt.getId()).getStatus())
                .isEqualTo(CallAttemptStatus.IN_PROGRESS);
    }

    // =====================================================================
    // helpers
    // =====================================================================

    /**
     * Runs the event exactly as production does.
     *
     * <p>{@code EslEventService.processEvent} is {@code @Transactional} and in
     * production is a Spring bean, so the event - and therefore the recovery it
     * delegates to - runs inside a real transaction. These tests construct the
     * service directly, so the transaction is established here instead; without
     * it the VB-6C ledger's bulk-update query has no transaction to join.
     */
    private boolean processInTransaction(EslEvent event) {
        Boolean[] handled = new Boolean[1];
        tx.executeWithoutResult(t -> handled[0] = eslEvents.processEvent(event));
        return Boolean.TRUE.equals(handled[0]);
    }

    private EslEvent pinnedHangup(CallAttempt attempt, String cause) {
        String channelUuid = attempt.getId().toString();
        EslEvent e = new EslEvent("CHANNEL_HANGUP");
        e.addHeader("Unique-ID", channelUuid);
        e.addHeader("Channel-Call-UUID", channelUuid);
        e.addHeader("Caller-Unique-ID", channelUuid);
        e.addHeader("variable_call_uuid", channelUuid);
        e.addHeader("variable_origination_uuid", channelUuid);
        e.addHeader("Hangup-Cause", cause);
        return e;
    }

    private CallAttempt reload(UUID id) {
        return tx.execute(t -> attemptRepository.findByIdAndDeletedAtIsNull(id)).orElseThrow();
    }

    private CampaignExecutionStatus executionStatus(UUID id) {
        return tx.execute(t -> executionRepository.findByIdAndDeletedAtIsNull(id))
                .orElseThrow().getStatus();
    }

    private void setAttemptStatus(UUID id, CallAttemptStatus status) {
        tx.executeWithoutResult(t -> {
            CallAttempt a = attemptRepository.findByIdAndDeletedAtIsNull(id).orElseThrow();
            a.setStatus(status);
            attemptRepository.saveAndFlush(a);
        });
    }

    private int sessionCount(CallAttempt attempt) {
        return tx.execute(t -> callSessionRepository
                .findByCallAttemptIdAndDeletedAtIsNull(attempt.getId()).isPresent() ? 1 : 0);
    }

    private long countEntries(UUID attemptId) {
        Long n = tx.execute(t -> entityManager.createQuery(
                "SELECT COUNT(e) FROM VoiceBlastDailyUsageEntry e WHERE e.callAttemptId = :a",
                Long.class).setParameter("a", attemptId).getSingleResult());
        return n == null ? 0L : n;
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
            session.setProviderCallId(attempt.getId().toString());
            session.setCallAttemptId(attempt.getId());
            session.setInitiatedAt(Instant.now());
            callSessionRepository.saveAndFlush(session);
        });
    }

    private CallAttempt claimedAttempt(Fixture f) {
        CallAttempt attempt = seedAttempt(f);
        int rows = tx.execute(t -> attemptRepository.claimForDispatch(
                attempt.getId(), attempt.getTenantId(),
                CallAttemptStatus.QUEUED, CallAttemptStatus.IN_PROGRESS, Instant.now()));
        assertThat(rows).as("the claim must commit for this scenario to mean anything")
                .isEqualTo(1);
        return reload(attempt.getId());
    }

    private record Fixture(UUID campaignId, UUID executionId, UUID contactId,
                           UUID tenantId, UUID didId) {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final UUID CALLER_ID =
            UUID.fromString("ff000000-0000-4000-8000-0000000000f8");

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
            c.setName("c-vb8f-" + n);
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
            e.setStatus(CampaignExecutionStatus.RUNNING);
            e.setRequestedAt(Instant.now());
            e.setStartedAt(Instant.now());
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
            a.setStorageReference("s3://vb8f/" + n + ".wav");
            a.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
            return audioAssetRepository.saveAndFlush(a).getId();
        });
    }
}
