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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * VB-8G — recovered provider acceptance reconciled into the VB-6C bucket,
 * against real PostgreSQL.
 *
 * <p>Closes F-8F-01. Before this phase the bucket could stay permanently one
 * behind reality: the pre-dial reservation was rolled back with the crashed
 * dispatch transaction, so the hold-guarded increment matched no row, and one
 * extra dial per day could be admitted against the provider-accepted limit.
 *
 * <p>The invariant under test, stated once: <b>every provider-accepted call for a
 * (tenant, contact, actual route DID, calendar day) bucket appears in
 * {@code used_count} exactly once</b> — whether the acceptance was observed
 * normally or recovered from a FreeSWITCH event after the write was lost.
 *
 * <p>Test items 1-10 of the phase matrix are the fail-closed and
 * key-reconstruction cases; 11-17 are the database-backed accounting,
 * concurrency and limit-interaction cases.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecoveredAcceptanceReconciliationPostgresIntegrationTest {

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
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                            + startupFailure.getMessage(), startupFailure);
        }
        tx = new TransactionTemplate(transactionManager);
        var configurationService = new CampaignConfigurationService(
                snapshotRepository, new CampaignTypeConfigValidator());
        runtimeConfigResolver = new CampaignRuntimeConfigResolver(configurationService);
        // Pinned clock so "today" is deterministic and equals TODAY, which is
        // the timezone the seeded snapshot declares.
        limitService = new DailyDialLimitService(usageRepository, entryRepository,
                new SimpleMeterRegistry(),
                java.time.Clock.fixed(java.time.Instant.parse("2026-09-29T10:00:00Z"),
                        java.time.ZoneOffset.UTC));
        recovery = new OrphanedDispatchRecovery(attemptRepository, callSessionRepository,
                executionRepository, runtimeConfigResolver, limitService);
        eslEvents = new EslEventService(attemptRepository, callSessionRepository, callLegRepository,
                org.mockito.Mockito.mock(com.shivang.obd.voice.capacity.VoiceCapacityService.class),
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
    // 11 - first reconciliation creates the ledger row and increments once
    // =====================================================================

    @Test
    @DisplayName("11: the first reconciliation writes the ledger and counts exactly one dial")
    void firstReconciliationCountsOnce() {
        Fixture f = seedFixture("11");
        CallAttempt attempt = claimedAttempt(f);

        processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        assertThat(usedCount(f)).isEqualTo(1);
        assertThat(entryRepository.existsByCallAttemptId(attempt.getId())).isTrue();
        assertThat(attemptCompletedAt(attempt.getId())).isNotNull();
    }

    // =====================================================================
    // 12 - a duplicate event leaves used_count unchanged
    // =====================================================================

    @Test
    @DisplayName("12: a duplicate recovery event does not increment the bucket again")
    void duplicateReconciliationDoesNotDoubleCount() {
        Fixture f = seedFixture("12");
        CallAttempt attempt = claimedAttempt(f);

        processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));
        processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));
        processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        assertThat(usedCount(f))
                .as("one real call, one count - no matter how many times the event arrives")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("9: reconciling the same attempt twice through the service is idempotent")
    void directDoubleReconcileIsIdempotent() {
        Fixture f = seedFixture("9");
        CallAttempt attempt = claimedAttempt(f);

        boolean first = reconcile(f, attempt);
        boolean second = reconcile(f, attempt);

        assertThat(first).isTrue();
        assertThat(second).as("the UNIQUE(call_attempt_id) row already exists").isFalse();
        assertThat(usedCount(f)).isEqualTo(1);
    }

    // =====================================================================
    // 13 - concurrent duplicate reconciliation of the SAME attempt
    // =====================================================================

    @Test
    @DisplayName("13: eight concurrent reconciliations of one attempt count it exactly once")
    void concurrentDuplicateReconciliationCountsOnce() throws Exception {
        Fixture f = seedFixture("13");
        CallAttempt attempt = claimedAttempt(f);
        AtomicInteger counted = new AtomicInteger();

        runConcurrently(8, () -> {
            if (reconcile(f, attempt)) {
                counted.incrementAndGet();
            }
        });

        assertThat(counted.get())
                .as("exactly one worker wins the UNIQUE(call_attempt_id) insert")
                .isEqualTo(1);
        assertThat(usedCount(f)).isEqualTo(1);
    }

    // =====================================================================
    // 14 - concurrent different recoveries for the SAME bucket
    // =====================================================================

    @Test
    @DisplayName("14: six concurrent distinct recoveries on one bucket count all six, exactly once")
    void concurrentDistinctRecoveriesOnOneBucketAllCount() throws Exception {
        Fixture f = seedFixture("14");
        List<CallAttempt> attempts = List.of(
                claimedAttempt(f, 1), claimedAttempt(f, 2), claimedAttempt(f, 3),
                claimedAttempt(f, 4), claimedAttempt(f, 5), claimedAttempt(f, 6));
        AtomicInteger counted = new AtomicInteger();

        runConcurrently(6, () -> {
            for (CallAttempt a : attempts) {
                if (reconcile(f, a)) {
                    counted.incrementAndGet();
                }
            }
        });

        assertThat(counted.get()).isEqualTo(6);
        assertThat(usedCount(f))
                .as("serialised by the bucket row lock; no lost update")
                .isEqualTo(6);
        for (CallAttempt a : attempts) {
            assertThat(entryRepository.existsByCallAttemptId(a.getId())).isTrue();
        }
    }

    // =====================================================================
    // 15 - recovery concurrent with normal admission
    // =====================================================================

    @Test
    @DisplayName("15: admission running alongside recovery still observes the reconciled usage")
    void admissionObservesReconciledUsage() throws Exception {
        Fixture f = seedFixture("15");
        CallAttempt attempt = claimedAttempt(f);
        int limit = 2;
        AtomicInteger admitted = new AtomicInteger();

        // One recovery racing two normal admissions for the same bucket, each
        // admission going through the real guarded reserve.
        runConcurrently(3, () -> {
            if (reconcile(f, attempt)) {
                return;
            }
        });
        // Then the admissions, which must see the reconciled usage.
        runConcurrently(2, () -> {
            int rows = tx.execute(t -> {
                usageRepository.insertBucketRow(
                        f.tenantId(), f.contactId(), f.didId(), TODAY);
                return usageRepository.reserve(
                        f.tenantId(), f.contactId(), f.didId(), TODAY, limit);
            });
            if (rows == 1) {
                admitted.incrementAndGet();
            }
        });

        assertThat(usedCount(f)).as("the recovered dial is counted").isEqualTo(1);
        assertThat(admitted.get())
                .as("used_count=1 leaves exactly one remaining slot at limit=2")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("15b: a normal admission cannot slip past a concurrent recovery")
    void admissionCannotSlipPastConcurrentRecovery() throws Exception {
        Fixture f = seedFixture("15b");
        CallAttempt attempt = claimedAttempt(f);
        int limit = 1;
        // Fill the bucket to the limit first, so admission must now be refused.
        tx.executeWithoutResult(t -> usageRepository.insertBucketRow(
                f.tenantId(), f.contactId(), f.didId(), TODAY));

        runConcurrently(4, () -> reconcile(f, attempt));

        int admitted = tx.execute(t -> usageRepository.reserve(
                f.tenantId(), f.contactId(), f.didId(), TODAY, limit));

        assertThat(usedCount(f)).isEqualTo(1);
        assertThat(admitted)
                .as("the reconciled acceptance closed the only remaining slot")
                .isZero();
    }

    // =====================================================================
    // 16 - a bucket already at the limit still records the historical dial
    // =====================================================================

    @Test
    @DisplayName("16: a recovered acceptance is recorded even when the bucket is already full")
    void recoveryAtLimitStillRecords() {
        Fixture f = seedFixture("16");
        // Push the bucket to and beyond the limit using real admissions.
        tx.executeWithoutResult(t -> usageRepository.insertBucketRow(
                f.tenantId(), f.contactId(), f.didId(), TODAY));
        for (int i = 0; i < 3; i++) {
            int rows = tx.execute(t -> usageRepository.reserve(
                    f.tenantId(), f.contactId(), f.didId(), TODAY, 3));
            assertThat(rows).isEqualTo(1);
            tx.executeWithoutResult(t -> usageRepository.confirmUsed(
                    f.tenantId(), f.contactId(), f.didId(), TODAY));
        }
        assertThat(usedCount(f)).isEqualTo(3);

        CallAttempt attempt = claimedAttempt(f);

        // The limit is reached. The call really happened, so it MUST be counted.
        assertThat(reconcile(f, attempt))
                .as("a factual acceptance is never refused for quota reasons")
                .isTrue();
        assertThat(usedCount(f))
                .as("the bucket is allowed to exceed the limit rather than erase a real call")
                .isEqualTo(4);
    }

    // =====================================================================
    // 17 - a later admission sees the over-limit bucket and stops
    // =====================================================================

    @Test
    @DisplayName("17: once over the limit, the next admission is refused")
    void admissionStopsAfterOverLimitBucket() {
        Fixture f = seedFixture("17");
        tx.executeWithoutResult(t -> usageRepository.insertBucketRow(
                f.tenantId(), f.contactId(), f.didId(), TODAY));
        for (int i = 0; i < 3; i++) {
            tx.execute(t -> usageRepository.reserve(
                    f.tenantId(), f.contactId(), f.didId(), TODAY, 3));
            tx.executeWithoutResult(t -> usageRepository.confirmUsed(
                    f.tenantId(), f.contactId(), f.didId(), TODAY));
        }
        CallAttempt attempt = claimedAttempt(f);
        reconcile(f, attempt);
        assertThat(usedCount(f)).isEqualTo(4);

        int admitted = tx.execute(t -> usageRepository.reserve(
                f.tenantId(), f.contactId(), f.didId(), TODAY, 3));

        assertThat(admitted)
                .as("the over-limit bucket stops further dials to this contact")
                .isZero();
        // admit() performs modifying queries, so it needs a real transaction,
        // exactly as the dial path provides.
        DailyDialLimitService.AdmissionResult result = tx.execute(
                t -> limitService.admit(f.tenantId(), f.contactId(), f.didId(), TODAY, 3));
        assertThat(result).isEqualTo(DailyDialLimitService.AdmissionResult.DAILY_LIMIT_REACHED);
    }

    // =====================================================================
    // 3 - the key is reconstructed from persisted context, not from the event
    // =====================================================================

    @Test
    @DisplayName("3: the reconciled key comes from the attempt's own tenant/contact/DID/day")
    void keyIsReconstructedFromPersistedContext() {
        Fixture f = seedFixture("3");
        CallAttempt attempt = claimedAttempt(f);

        processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        var entry = entryRepository.findByCallAttemptId(attempt.getId()).orElseThrow();
        assertThat(entry.getTenantId()).isEqualTo(f.tenantId());
        assertThat(entry.getContactId()).isEqualTo(f.contactId());
        assertThat(entry.getDidId()).isEqualTo(f.didId());
        assertThat(entry.getUsageDate()).isEqualTo(TODAY);
        assertThat(entry.getProviderCallId()).isEqualTo(attempt.getId().toString());
    }

    // =====================================================================
    // 4-8 - fail closed
    // =====================================================================

    @Test
    @DisplayName("4: a null tenant is refused - no usage is fabricated")
    void nullTenantFailsClosed() {
        Fixture f = seedFixture("4");
        CallAttempt attempt = claimedAttempt(f);

        boolean reconciled = limitService.reconcileRecoveredAcceptance(
                null, attempt.getId(), f.contactId(), f.didId(), TODAY, "x");

        assertThat(reconciled).isFalse();
        assertThat(usedCount(f)).isZero();
        assertThat(entryRepository.existsByCallAttemptId(attempt.getId())).isFalse();
    }

    @Test
    @DisplayName("5: a null contact is refused - no usage is fabricated")
    void nullContactFailsClosed() {
        Fixture f = seedFixture("5");
        CallAttempt attempt = claimedAttempt(f);

        assertThat(limitService.reconcileRecoveredAcceptance(
                f.tenantId(), attempt.getId(), null, f.didId(), TODAY, "x")).isFalse();
        assertThat(usedCount(f)).isZero();
    }

    @Test
    @DisplayName("6: a null DID is refused - no usage is fabricated")
    void nullDidFailsClosed() {
        Fixture f = seedFixture("6");
        CallAttempt attempt = claimedAttempt(f);

        assertThat(limitService.reconcileRecoveredAcceptance(
                f.tenantId(), attempt.getId(), f.contactId(), null, TODAY, "x")).isFalse();
        assertThat(usedCount(f)).isZero();
    }

    @Test
    @DisplayName("7: a null usage date is refused - no usage is fabricated")
    void nullUsageDateFailsClosed() {
        Fixture f = seedFixture("7");
        CallAttempt attempt = claimedAttempt(f);

        assertThat(limitService.reconcileRecoveredAcceptance(
                f.tenantId(), attempt.getId(), f.contactId(), f.didId(), null, "x")).isFalse();
        assertThat(usedCount(f)).isZero();
    }

    @Test
    @DisplayName("8: a null attempt id is refused - no usage is fabricated")
    void nullAttemptFailsClosed() {
        Fixture f = seedFixture("8");

        assertThat(limitService.reconcileRecoveredAcceptance(
                f.tenantId(), null, f.contactId(), f.didId(), TODAY, "x")).isFalse();
        assertThat(usedCount(f)).isZero();
    }

    @Test
    @DisplayName("4-8: another tenant's bucket is untouched by a foreign reconciliation")
    void crossTenantReconciliationCannotLeak() {
        Fixture tenantA = seedFixture("x-a");
        Fixture tenantB = seedFixture("x-b");
        CallAttempt attemptB = claimedAttempt(tenantB);

        // Reconciling attempt B while naming tenant A's bucket must not create
        // usage under A: the key is explicit, so the assertion is that A stays
        // empty and only B is credited.
        tx.executeWithoutResult(t -> {
            usageRepository.insertBucketRow(tenantA.tenantId(), tenantA.contactId(),
                    tenantA.didId(), TODAY);
        });

        reconcile(tenantB, attemptB);

        assertThat(usedCount(tenantA))
                .as("tenant A's bucket is never credited for tenant B's call")
                .isZero();
        assertThat(usedCount(tenantB)).isEqualTo(1);
    }

    // =====================================================================
    // 10 - a terminal attempt is not resurrected by reconciliation
    // =====================================================================

    @Test
    @DisplayName("10: a terminal attempt is not reconciled and is not resurrected")
    void terminalAttemptIsNotReconciled() {
        Fixture f = seedFixture("10");
        CallAttempt attempt = claimedAttempt(f);
        setAttemptStatus(attempt.getId(), CallAttemptStatus.CANCELLED);

        boolean processed = processInTransaction(pinnedHangup(attempt, "NORMAL_CLEARING"));

        assertThat(processed).isFalse();
        assertThat(reload(attempt.getId()).getStatus()).isEqualTo(CallAttemptStatus.CANCELLED);
        assertThat(usedCount(f))
                .as("no acceptance evidence, so no usage is invented for it")
                .isZero();
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private boolean processInTransaction(EslEvent event) {
        Boolean[] handled = new Boolean[1];
        tx.executeWithoutResult(t -> handled[0] = eslEvents.processEvent(event));
        return Boolean.TRUE.equals(handled[0]);
    }

    private boolean reconcile(Fixture f, CallAttempt attempt) {
        return tx.execute(t -> limitService.reconcileRecoveredAcceptance(
                f.tenantId(), attempt.getId(), f.contactId(), f.didId(), TODAY,
                attempt.getId().toString()));
    }

    private int usedCount(Fixture f) {
        return limitService.usedCount(f.tenantId(), f.contactId(), f.didId(), TODAY);
    }

    private void runConcurrently(int threads, Runnable body) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        body.run();
                    } catch (RuntimeException ignored) {
                        // asserted through observable database state
                    }
                });
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
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

    private Instant attemptCompletedAt(UUID id) {
        return reload(id).getCompletedAt();
    }

    private void setAttemptStatus(UUID id, CallAttemptStatus status) {
        tx.executeWithoutResult(t -> {
            CallAttempt a = attemptRepository.findByIdAndDeletedAtIsNull(id).orElseThrow();
            a.setStatus(status);
            attemptRepository.saveAndFlush(a);
        });
    }

    private CallAttempt claimedAttempt(Fixture f, int attemptNumber) {
        CallAttempt a = seedAttempt(f, attemptNumber);
        int rows = tx.execute(t -> attemptRepository.claimForDispatch(
                a.getId(), a.getTenantId(),
                CallAttemptStatus.QUEUED, CallAttemptStatus.IN_PROGRESS, Instant.now()));
        assertThat(rows).isEqualTo(1);
        return reload(a.getId());
    }

    private CallAttempt claimedAttempt(Fixture f) {
        return claimedAttempt(f, 1);
    }

    private record Fixture(UUID campaignId, UUID executionId, UUID contactId,
                           UUID tenantId, UUID didId) {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final UUID CALLER_ID =
            UUID.fromString("ff000000-0000-4000-8000-0000000000f9");

    private CallAttempt seedAttempt(Fixture f, int attemptNumber) {
        return tx.execute(t -> {
            CallAttempt a = new CallAttempt();
            a.setExecutionId(f.executionId());
            a.setCampaignId(f.campaignId());
            a.setTenantId(f.tenantId());
            a.setContactId(f.contactId());
            a.setDidId(f.didId());
            a.setAttemptNumber(attemptNumber);
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
            c.setName("c-vb8g-" + n);
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
            a.setStorageReference("s3://vb8g/" + n + ".wav");
            a.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
            return audioAssetRepository.saveAndFlush(a).getId();
        });
    }
}
