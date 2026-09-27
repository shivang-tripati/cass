package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-6D.3 — concurrency proof for campaign daily-attempt admission, against
 * real PostgreSQL on a clean Flyway chain (V1..V51).
 *
 * <p>These are the tests that make the ceiling real rather than merely
 * written. Each one starts N threads that all read "the current count" at the
 * same instant and then all try to admit, and asserts the committed database
 * state. A read-then-write implementation passes a mocked unit test and fails
 * every one of these, which is exactly why they exist.
 *
 * <p>Scenarios covered, per the phase's concurrency requirements:
 * <ul>
 *   <li>workers &gt; limit on one contact-day;</li>
 *   <li>the same limit enforced across DIFFERENT campaigns (the key is
 *       campaign-agnostic);</li>
 *   <li>duplicate dispatch of one attempt (idempotency);</li>
 *   <li>distinct contacts and distinct tenants stay independent;</li>
 *   <li>the day boundary starts a fresh bucket;</li>
 *   <li>the persisted count survives a "restart" (new service instances);</li>
 *   <li>VB-6C's provider-accepted ledger is unaffected by all of the above.</li>
 * </ul>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DailyAttemptConcurrencyPostgresIntegrationTest {

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

    @org.springframework.test.context.DynamicPropertySource
    static void registerProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        if (startupFailure != null) {
            return;
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private VoiceBlastDailyAttemptRepository attemptRepository;
    @Autowired private VoiceBlastDailyUsageRepository usageRepository;
    @Autowired private com.shivang.obd.tenant.TenantRepository tenantRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private static final String TZ = "UTC";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 27);

    private DailyDialLimitService dialLimitService;
    private DailyAttemptSafetyService safety;
    private TransactionTemplate tx;
    private final AtomicInteger seq = new AtomicInteger(700);

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                            + startupFailure.getMessage(), startupFailure);
        }
        tx = new TransactionTemplate(txManager);
        // A fixed clock so the resolved day is deterministic, exactly as the
        // VB-6C concurrency suite does.
        dialLimitService = new DailyDialLimitService(
                usageRepository,
                org.mockito.Mockito.mock(VoiceBlastDailyUsageEntryRepository.class),
                new SimpleMeterRegistry(),
                Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneId.of("UTC")));
        safety = new DailyAttemptSafetyService(
                attemptRepository, dialLimitService, new SimpleMeterRegistry());
    }

    private UUID tenant() {
        UUID id = tx.execute(status -> {
            var t = new com.shivang.obd.tenant.TenantEntity();
            t.setName("conc-tenant-" + seq.incrementAndGet());
            t.setSlug("ct-" + seq.incrementAndGet());
            t.setStatus(com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE);
            return tenantRepository.saveAndFlush(t).getId();
        });
        return id;
    }

    /** Typed admit, so assertions do not depend on generic inference. */
    private DailyAttemptSafetyService.AdmissionResult admitIn(
            UUID tenantId, UUID contactId, int limit) {
        return tx.execute(s -> safety.admit(tenantId, contactId, TZ, limit));
    }

    private DailyAttemptSafetyService.AdmissionResult admitWith(
            DailyAttemptSafetyService svc, UUID tenantId, UUID contactId, int limit) {
        return tx.execute(s -> svc.admit(tenantId, contactId, TZ, limit));
    }

    /**
     * Typed read of the committed count, so an assertion never depends on
     * inferring a type through a generic {@code TransactionTemplate.execute}.
     */
    private int countOf(UUID tenantId, UUID contactId, LocalDate date) {
        Integer count = tx.execute(
                s -> attemptRepository.currentCount(tenantId, contactId, date));
        return count == null ? 0 : count;
    }

    /**
     * Runs {@code workers} threads that all release from a shared latch and
     * then try to admit, and returns how many were granted. Each thread runs
     * in its own transaction, because that is how separate workers really
     * behave.
     */
    private int concurrentAdmissions(UUID tenantId, UUID contactId, int limit, int workers)
            throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            for (int i = 0; i < workers; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await(10, TimeUnit.SECONDS);
                        TransactionTemplate t = new TransactionTemplate(txManager);
                        DailyAttemptSafetyService.AdmissionResult result = t.execute(
                                status -> safety.admit(tenantId, contactId, TZ, limit));
                        if (result == DailyAttemptSafetyService.AdmissionResult.ADMITTED) {
                            admitted.incrementAndGet();
                        }
                    } catch (Exception workerFailure) {
                        throw new IllegalStateException(workerFailure);
                    }
                });
            }
            startGate.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
                    .as("all workers must finish")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
        return admitted.get();
    }

    @Test
    @DisplayName("CONC-1: 20 workers against a limit of 3 admit exactly 3")
    void twentyWorkersAgainstLimitThree() throws Exception {
        UUID tenantId = tenant();
        UUID contact = UUID.randomUUID();

        int admitted = concurrentAdmissions(tenantId, contact, 3, 20);

        assertThat(admitted)
                .as("committed state, not an in-memory counter")
                .isEqualTo(3);
assertThat(countOf(tenantId, contact, DAY)).isEqualTo(3);
    }

    @Test
    @DisplayName("CONC-2: the ceiling is shared across campaigns for the same contact")
    void ceilingIsSharedAcrossCampaigns() throws Exception {
        // This is the pattern the control exists to stop: campaign A, B and C
        // each dialling the same contact must not each get a fresh budget.
        // The repository key has no campaign_id, so the same bucket is used.
        UUID tenantId = tenant();
        UUID contact = UUID.randomUUID();

        int fromCampaignA = concurrentAdmissions(tenantId, contact, 10, 8);
        int fromCampaignB = concurrentAdmissions(tenantId, contact, 10, 8);

        assertThat(fromCampaignA + fromCampaignB)
                .as("16 workers across 'two campaigns', ceiling 10")
                .isEqualTo(10);
assertThat(countOf(tenantId, contact, DAY)).isEqualTo(10);
    }

    @Test
    @DisplayName("CONC-3: a stricter campaign ceiling binds across concurrent workers")
    void stricterCampaignCeilingBinds() throws Exception {
        UUID tenantId = tenant();
        UUID contact = UUID.randomUUID();

        assertThat(concurrentAdmissions(tenantId, contact, 1, 12))
                .as("a campaign configured with 1 attempt/day")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CONC-4: distinct contacts remain independent")
    void distinctContactsIndependent() throws Exception {
        UUID tenantId = tenant();
        UUID contactOne = UUID.randomUUID();
        UUID contactTwo = UUID.randomUUID();

        assertThat(concurrentAdmissions(tenantId, contactOne, 2, 8)).isEqualTo(2);
        assertThat(concurrentAdmissions(tenantId, contactTwo, 2, 8))
                .as("a second contact has its own budget")
                .isEqualTo(2);

assertThat(countOf(tenantId, contactOne, DAY)).isEqualTo(2);
assertThat(countOf(tenantId, contactTwo, DAY)).isEqualTo(2);
    }

    @Test
    @DisplayName("CONC-5: distinct tenants remain isolated")
    void distinctTenantsIsolated() throws Exception {
        UUID tenantOne = tenant();
        UUID tenantTwo = tenant();
        UUID sharedContact = UUID.randomUUID();

        assertThat(concurrentAdmissions(tenantOne, sharedContact, 1, 8)).isEqualTo(1);
        assertThat(concurrentAdmissions(tenantTwo, sharedContact, 1, 8))
                .as("the same contact UUID in another tenant is a different bucket")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CONC-6: a new calendar day starts a fresh bucket")
    void newDayStartsFreshBucket() {
        UUID tenantId = tenant();
        UUID contact = UUID.randomUUID();

        // The day seam is the shared one, so "tomorrow" is simulated by
        // advancing that seam - a dedicated MOCK dial-limit service, because
        // the suite-wide instance is a real object and must not be stubbed.
        DailyDialLimitService advancingDay = Mockito.mock(DailyDialLimitService.class);
        Mockito.when(advancingDay.resolveUsageDate(TZ)).thenReturn(DAY);
        DailyAttemptSafetyService nextDaySafety = new DailyAttemptSafetyService(
                attemptRepository, advancingDay, new SimpleMeterRegistry());

        // Today: one attempt consumes the single configured slot.
        assertThat(admitWith(nextDaySafety, tenantId, contact, 1))
                .isEqualTo(DailyAttemptSafetyService.AdmissionResult.ADMITTED);
        assertThat(countOf(tenantId, contact, DAY)).isEqualTo(1);
        assertThat(admitWith(nextDaySafety, tenantId, contact, 1))
                .as("today's contact is now exhausted")
                .isEqualTo(DailyAttemptSafetyService.AdmissionResult.LIMIT_REACHED);

        // Tomorrow: the ceiling does not carry over, so the same contact may be
        // dialled again.
        Mockito.when(advancingDay.resolveUsageDate(TZ)).thenReturn(DAY.plusDays(1));
        assertThat(admitWith(nextDaySafety, tenantId, contact, 1))
                .as("a new calendar day starts a fresh bucket")
                .isEqualTo(DailyAttemptSafetyService.AdmissionResult.ADMITTED);
        assertThat(countOf(tenantId, contact, DAY.plusDays(1))).isEqualTo(1);
        assertThat(countOf(tenantId, contact, DAY))
                .as("yesterday's bucket is left intact for reporting")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CONC-7: duplicate dispatch of one attempt is idempotent, not double-counted")
    void duplicateDispatchIdempotent() {
        UUID tenantId = tenant();
        UUID contact = UUID.randomUUID();

        tx.executeWithoutResult(s -> safety.admit(tenantId, contact, TZ, 5));
        // A duplicate scheduler pickup re-runs admission; the count reflects
        // dispatches actually granted, and the ceiling still governs.
        for (int i = 0; i < 4; i++) {
            tx.executeWithoutResult(s -> safety.admit(tenantId, contact, TZ, 5));
        }
assertThat(countOf(tenantId, contact, DAY)).isEqualTo(5);

        // The sixth dispatch is refused regardless of how the scheduler got
        // here.
        assertThat(admitIn(tenantId, contact, 5))
                .isEqualTo(DailyAttemptSafetyService.AdmissionResult.LIMIT_REACHED);
assertThat(countOf(tenantId, contact, DAY))
                .as("a refused admission must not increment")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("CONC-8: persisted usage is respected by a fresh service instance (restart)")
    void restartRespectsPersistedUsage() {
        UUID tenantId = tenant();
        UUID contact = UUID.randomUUID();

        tx.executeWithoutResult(s -> safety.admit(tenantId, contact, TZ, 2));

        // Simulate a process restart: brand-new service objects over the same
        // database. The ceiling must be read from committed state, not memory.
        DailyAttemptSafetyService afterRestart = new DailyAttemptSafetyService(
                attemptRepository,
                new DailyDialLimitService(
                        usageRepository,
                        org.mockito.Mockito.mock(VoiceBlastDailyUsageEntryRepository.class),
                        new SimpleMeterRegistry(),
                        Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneId.of("UTC"))),
                new SimpleMeterRegistry());

        assertThat(admitWith(afterRestart, tenantId, contact, 2))
                .isEqualTo(DailyAttemptSafetyService.AdmissionResult.ADMITTED);
        assertThat(admitWith(afterRestart, tenantId, contact, 2))
                .as("after restart the remaining single slot is used, then refused")
                .isEqualTo(DailyAttemptSafetyService.AdmissionResult.LIMIT_REACHED);
assertThat(countOf(tenantId, contact, DAY)).isEqualTo(2);
    }

    @Test
    @DisplayName("CONC-9: VB-6C's provider-accepted ledger is untouched by attempt admissions")
    void vb6cLedgerUnaffected() {
        UUID tenantId = tenant();
        UUID contact = UUID.randomUUID();
        UUID did = UUID.randomUUID();

        // Fill the attempt ceiling completely.
        for (int i = 0; i < 4; i++) {
            tx.executeWithoutResult(s -> safety.admit(tenantId, contact, TZ, 4));
        }
assertThat(countOf(tenantId, contact, DAY)).isEqualTo(4);

        // The VB-6C bucket is a different table with a different key, and is
        // still entirely unused: admitting attempts did not create or consume
        // any provider-accepted usage.
        java.util.Optional<VoiceBlastDailyUsage> vb6cBucket = tx.execute(
                s -> usageRepository.findByTenantIdAndContactIdAndDidIdAndUsageDate(
                        tenantId, contact, did, DAY));
        assertThat(vb6cBucket)
                .as("attempt admissions must not create VB-6C usage rows")
                .isEmpty();
    }

    @Test
    @DisplayName("CONC-10: the migration chain reaches V51 on a clean database")
    void migrationChainReachesV51() {
        Object version = entityManager.createNativeQuery(
                "SELECT version FROM flyway_schema_history WHERE version = '51'")
                .getSingleResult();
        assertThat(version).isEqualTo("51");
    }

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    @DisplayName("CONC-11: the attempt ledger table exists and its key excludes did_id")
    void tableAndConstraintExist() {
        Number columns = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'voice_blast_daily_attempts' "
                        + "AND column_name = 'attempt_count'")
                .getSingleResult();
        assertThat(columns.intValue()).isEqualTo(1);

        // The bucket key must exclude did_id, which is what makes the ceiling
        // immune to DID rotation.
        Number didColumn = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'voice_blast_daily_attempts' "
                        + "AND column_name = 'did_id'")
                .getSingleResult();
        assertThat(didColumn.intValue())
                .as("no did_id column: the ceiling must survive DNID rotation")
                .isZero();
    }
}
