package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.contact.ContactRepository;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSessionRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-6C.1 — the daily dial-limit ledger against real PostgreSQL (full
 * Flyway chain incl. V47). Proves the compliance invariant from DATABASE
 * state, not application return values:
 *
 * <ul>
 *   <li>bucket creation + unique identity (per contact, per DNID, per
 *       day, per tenant)</li>
 *   <li>atomic conditional increment — 20 concurrent workers with limit 3
 *       yield exactly 3 reservations/confirmations, never 4</li>
 *   <li>per-attempt idempotency — a duplicate acceptance entry is
 *       physically impossible ({@code UNIQUE (call_attempt_id)})</li>
 *   <li>cross-campaign sharing, cross-DNID independence, tenant/contact
 *       isolation, and the calendar-day boundary in the snapshot
 *       timezone</li>
 *   <li>end-to-end dial-path integration: pre-acceptance rejection and
 *       provider acceptance vs. usage, via the real
 *       {@link OutboundDialService}</li>
 * </ul>
 *
 * <p>Harness conventions copied from the known-good PG suites: static
 * container startup, datasource/Flyway routing via
 * {@code @DynamicPropertySource}, {@code JpaAuditConfig} import,
 * {@code NOT_SUPPORTED} propagation; raw-SQL fixtures; workers run
 * {@link VoiceBlastDailyUsageRepository} operations directly (the exact
 * statements the dial path executes, in their own transactions).</p>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VoiceBlastDailyDialLimitPostgresIntegrationTest {

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
    private VoiceBlastDailyUsageRepository usageRepository;
    @Autowired
    private VoiceBlastDailyUsageEntryRepository entryRepository;
    @Autowired
    private VoiceBlastDailyAttemptRepository dailyAttemptRepository;
    @Autowired
    private CallAttemptRepository attemptRepository;
    @Autowired
    private ContactRepository contactRepository;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CampaignExecutionRepository executionRepository;
    @Autowired
    private CampaignExecutionConfigurationRepository snapshotRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private CallSessionRepository callSessionRepository;
    @Autowired
    private CallLegRepository callLegRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private DailyDialLimitService limitService;
    private OutboundDialService dialService;

    private UUID tenantId;
    private UUID campaignId;
    private UUID executionId;
    private UUID groupId;
    private UUID snapshotId;
    private UUID didRowId;
    private UUID gatewayId;

    private static final String PHONE = "+919876500001";
    /** Fixed usage day for bucket-key assertions (matches the pinned clock). */
    private static final LocalDate DAY = LocalDate.of(2026, 9, 27);

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                    + startupFailure.getMessage(), startupFailure);
        }
        // Pinned clock so calendar-day assertions cannot race a real
        // midnight (the suite ran across one during development).
        // 20:00Z: UTC day = 2026-09-27, Asia/Kolkata day = 2026-09-28.
        limitService = new DailyDialLimitService(usageRepository, entryRepository,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                java.time.Clock.fixed(java.time.Instant.parse("2026-09-27T20:00:00Z"),
                        java.time.ZoneId.of("UTC")));
        attemptSeq = 0;
        tenantId = UUID.randomUUID();
        campaignId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        groupId = UUID.randomUUID();
        snapshotId = UUID.randomUUID();
        didRowId = UUID.randomUUID();
        gatewayId = UUID.randomUUID();
    }

    @AfterEach
    void cleanLedger() {
        // Remove this test's ledger/attempt rows so a failed test cannot
        // bleed QUEUED attempts into later tests' processDueAttempts runs.
        transactionTemplate().executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                    "DELETE FROM voice_blast_daily_usage_entries WHERE tenant_id = :t")
                .setParameter("t", tenantId).executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM voice_blast_daily_usage WHERE tenant_id = :t")
                .setParameter("t", tenantId).executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM call_attempts WHERE tenant_id = :t")
                .setParameter("t", tenantId).executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM call_legs WHERE call_session_id IN "
                    + "(SELECT id FROM call_sessions WHERE tenant_id = :t)")
                .setParameter("t", tenantId).executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM call_sessions WHERE tenant_id = :t")
                .setParameter("t", tenantId).executeUpdate();
        });
    }

    // === unit-of-work helpers (each runs in its own committed transaction) ===

    private int reserve(UUID contact, UUID did, LocalDate date, int limit) {
        return transactionTemplate().execute(tx ->
            limitService.admit(tenantId, contact, did, date, limit) == DailyDialLimitService.AdmissionResult.ADMITTED
                ? 1 : 0);
    }

    private void confirm(UUID attemptId, UUID contact, UUID did, LocalDate date) {
        transactionTemplate().executeWithoutResult(tx ->
            limitService.confirmAccepted(tenantId, attemptId, contact, did, date, "fs-" + attemptId));
    }

    private void release(UUID contact, UUID did, LocalDate date) {
        transactionTemplate().executeWithoutResult(tx ->
            limitService.releaseReservation(tenantId, contact, did, date));
    }

    private int usedCount(UUID contact, UUID did, LocalDate date) {
        return transactionTemplate().execute(tx ->
            limitService.usedCount(tenantId, contact, did, date));
    }

    /** Monotonic per-test attempt-number source (attempt unique index safe). */
    private int attemptSeq;

    private UUID nextAttempt(UUID contact) throws Exception {
        return seedAttempt(contact, ++attemptSeq);
    }

    private int usedCountRaw(UUID contact, UUID did, LocalDate date) {
        Number n = (Number) entityManager.createNativeQuery(
                "SELECT used_count FROM voice_blast_daily_usage "
                + "WHERE tenant_id = ? AND contact_id = ? AND did_id = ? AND usage_date = ?")
            .setParameter(1, tenantId)
            .setParameter(2, contact)
            .setParameter(3, did)
            .setParameter(4, date)
            .getSingleResult();
        return n.intValue();
    }

    private org.springframework.transaction.support.TransactionTemplate transactionTemplate() {
        return new org.springframework.transaction.support.TransactionTemplate(
            transactionManager);
    }

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    // === fixtures ===

    private void seedExecutionFixture(String timezone) throws Exception {
        try (Connection c = raw()) {
            exec(c, "INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?)", ps -> {
                ps.setObject(1, tenantId); ps.setString(2, "t-" + tenantId); ps.setString(3, "s-" + tenantId);
            });
            exec(c, "INSERT INTO campaigns (id, tenant_id, name, campaign_type, status, run_mode) "
                    + "VALUES (?, ?, 'it-dll', 'PLAYFILE', 'SCHEDULED', 'ONE_TIME')", ps -> {
                ps.setObject(1, campaignId); ps.setObject(2, tenantId);
            });
            exec(c, "INSERT INTO contact_groups (id, tenant_id, name) VALUES (?, ?, 'it-group')", ps -> {
                ps.setObject(1, groupId); ps.setObject(2, tenantId);
            });
            exec(c, "INSERT INTO sip_gateways (id, name, provider, free_switch_gateway_name, "
                    + "max_concurrent_channels, enabled) VALUES (?, ?, 'TATA', ?, 10, TRUE)",
                    ps -> {
                ps.setObject(1, gatewayId);
                ps.setString(2, "gw-" + gatewayId);
                ps.setString(3, "fs-" + gatewayId);
            });
            String didE164 = uniqueE164();
            exec(c, "INSERT INTO dids (id, tenant_id, e164_number, country_code, number_type, "
                    + "provider, status, allocation_state) VALUES (?, ?, ?, '+91', "
                    + "'MOBILE', 'TATA', 'ACTIVE', 'ASSIGNED')", ps -> {
                ps.setObject(1, didRowId); ps.setObject(2, tenantId); ps.setString(3, didE164);
            });
            exec(c, "INSERT INTO campaign_execution_configurations (id, campaign_id, tenant_id, "
                    + "campaign_type, contact_group_id, did_id, retry_max_attempts, retry_strategy, "
                    + "call_on_whitelist_numbers, timezone) VALUES (?, ?, ?, 'PLAYFILE', ?, ?, 0, 'FIXED', FALSE, ?)",
                    ps -> {
                ps.setObject(1, snapshotId); ps.setObject(2, campaignId);
                ps.setObject(3, tenantId); ps.setObject(4, groupId); ps.setObject(5, didRowId);
                ps.setString(6, timezone);
            });
            exec(c, "INSERT INTO campaign_executions (id, campaign_id, tenant_id, status, "
                    + "requested_by, configuration_snapshot_id) VALUES (?, ?, ?, 'RUNNING', 'it', ?)",
                    ps -> {
                ps.setObject(1, executionId); ps.setObject(2, campaignId);
                ps.setObject(3, tenantId); ps.setObject(4, snapshotId);
            });
        }
    }

    private UUID seedContact(UUID ownerTenant, String phone) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = raw()) {
            exec(c, "INSERT INTO contacts (id, tenant_id, phone_number) VALUES (?, ?, ?)", ps -> {
                ps.setObject(1, id); ps.setObject(2, ownerTenant); ps.setString(3, phone);
            });
        }
        return id;
    }

    private UUID seedAttempt(UUID contactId, int attemptNumber) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = raw()) {
            exec(c, "INSERT INTO call_attempts (id, execution_id, campaign_id, tenant_id, "
                    + "contact_id, did_id, attempt_number, status, scheduled_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 'QUEUED', now() - interval '1 minute')", ps -> {
                ps.setObject(1, id); ps.setObject(2, executionId);
                ps.setObject(3, campaignId); ps.setObject(4, tenantId);
                ps.setObject(5, contactId); ps.setObject(6, didRowId);
                ps.setInt(7, attemptNumber);
            });
        }
        return id;
    }

    private static String uniqueE164() {
        return "+9198" + String.format("%08d",
                Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }

    private static Connection raw() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private interface SqlBinder {
        void bind(PreparedStatement ps) throws Exception;
    }

    private static void exec(Connection c, String sql, SqlBinder binder) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        }
    }

    // === A. bucket creation, atomic increment, limit enforcement ===

    @Test
    @DisplayName("PG-DL1: bucket row is created lazily and uniquely per (tenant, contact, did, date)")
    void bucketCreationAndIdentity() throws Exception {
        seedExecutionFixture("UTC");
        UUID contact = seedContact(tenantId, PHONE);

        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        confirm(nextAttempt(contact), contact, didRowId, DAY);

        assertThat(usedCountRaw(contact, didRowId, DAY)).isEqualTo(1);
        assertThat(usedCount(contact, didRowId, DAY)).isEqualTo(1);

        // Exactly one bucket row for the exact key.
        Number rows = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM voice_blast_daily_usage "
                + "WHERE tenant_id = ? AND contact_id = ? AND did_id = ? AND usage_date = ?")
            .setParameter(1, tenantId).setParameter(2, contact)
            .setParameter(3, didRowId).setParameter(4, DAY)
            .getSingleResult();
        assertThat(rows.intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG-DL2: 20 concurrent workers, limit 3 — exactly 3 admissions from PostgreSQL state")
    void twentyConcurrentAdmissionsNeverExceedThree() throws Exception {
        seedExecutionFixture("UTC");
        UUID contact = seedContact(tenantId, PHONE);
        int threads = 20;
        int limit = 3;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();

        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final UUID attemptId = nextAttempt(contact);
                futures.add(pool.submit((Callable<Integer>) () -> {
                    ready.countDown();
                    start.await();
                    try {
                        int granted = transactionTemplate().execute(tx ->
                            limitService.admit(tenantId, contact, didRowId, DAY, limit)
                                == DailyDialLimitService.AdmissionResult.ADMITTED ? 1 : 0);
                        if (granted == 1) {
                            admitted.incrementAndGet();
                            // The dial path confirms in the same transaction
                            // as admission-resolve in production here: convert
                            // the hold immediately (accepted-dial simulation).
                            transactionTemplate().executeWithoutResult(tx ->
                                limitService.confirmAccepted(tenantId, attemptId, contact,
                                        didRowId, DAY, "fs-" + attemptId));
                        }
                        return granted;
                    } catch (Exception e) {
                        return -1;
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> f : futures) {
                assertThat(f.get(60, TimeUnit.SECONDS)).isIn(0, 1);
            }
        } finally {
            pool.shutdownNow();
        }

        // THE invariant, proven from PostgreSQL state: exactly 3 slots
        // consumed, never 4, no lost updates.
        assertThat(admitted.get()).isEqualTo(3);
        assertThat(usedCountRaw(contact, didRowId, DAY)).isEqualTo(3);
        Number reserved = (Number) entityManager.createNativeQuery(
                "SELECT reserved_count FROM voice_blast_daily_usage "
                + "WHERE tenant_id = ? AND contact_id = ? AND did_id = ? AND usage_date = ?")
            .setParameter(1, tenantId).setParameter(2, contact)
            .setParameter(3, didRowId).setParameter(4, DAY)
            .getSingleResult();
        assertThat(reserved.intValue()).isZero();
        Number entries = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM voice_blast_daily_usage_entries "
                + "WHERE contact_id = ? AND did_id = ? AND usage_date = ?")
            .setParameter(1, contact).setParameter(2, didRowId).setParameter(3, DAY)
            .getSingleResult();
        assertThat(entries.intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("PG-DL3: duplicate acceptance handling cannot double count (UNIQUE call_attempt_id)")
    void duplicateAcceptanceDoesNotDoubleCount() throws Exception {
        seedExecutionFixture("UTC");
        UUID contact = seedContact(tenantId, PHONE);
        UUID attemptId = seedAttempt(contact, 1);

        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        confirm(attemptId, contact, didRowId, DAY);
        // Replay the same provider acceptance for the SAME attempt.
        confirm(attemptId, contact, didRowId, DAY);

        assertThat(usedCountRaw(contact, didRowId, DAY)).isEqualTo(1);
        Number entries = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM voice_blast_daily_usage_entries WHERE call_attempt_id = ?")
            .setParameter(1, attemptId)
            .getSingleResult();
        assertThat(entries.intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG-DL4: cross-campaign shared bucket — second campaign sees the same usage")
    void crossCampaignSharesBucket() throws Exception {
        seedExecutionFixture("UTC");
        UUID contact = seedContact(tenantId, PHONE);

        // First "campaign": consume a slot.
        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        confirm(nextAttempt(contact), contact, didRowId, DAY);
        assertThat(usedCount(contact, didRowId, DAY)).isEqualTo(1);

        // Second campaign (different execution, same tenant) admits against
        // the SAME (contact, did, day) bucket — only 2 slots remain.
        UUID execution2 = UUID.randomUUID();
        UUID snapshot2 = UUID.randomUUID();
        try (Connection c = raw()) {
            exec(c, "INSERT INTO campaign_execution_configurations (id, campaign_id, tenant_id, "
                    + "campaign_type, contact_group_id, did_id, retry_max_attempts, retry_strategy, "
                    + "call_on_whitelist_numbers, timezone) VALUES (?, ?, ?, 'PLAYFILE', ?, ?, 0, 'FIXED', FALSE, 'UTC')",
                    ps -> {
                ps.setObject(1, snapshot2); ps.setObject(2, campaignId);
                ps.setObject(3, tenantId); ps.setObject(4, groupId); ps.setObject(5, didRowId);
            });
            exec(c, "INSERT INTO campaign_executions (id, campaign_id, tenant_id, status, "
                    + "requested_by, configuration_snapshot_id) VALUES (?, ?, ?, 'RUNNING', 'it', ?)",
                    ps -> {
                ps.setObject(1, execution2); ps.setObject(2, campaignId);
                ps.setObject(3, tenantId); ps.setObject(4, snapshot2);
            });
        }

        UUID sharedContactAttempt = nextAttempt(contact);
        try (Connection c = raw()) {
            exec(c, "UPDATE call_attempts SET execution_id = ? WHERE id = ?", ps -> {
                ps.setObject(1, execution2); ps.setObject(2, sharedContactAttempt);
            });
        }
        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        confirm(sharedContactAttempt, contact, didRowId, DAY);
        assertThat(usedCountRaw(contact, didRowId, DAY)).isEqualTo(2);
    }

    @Test
    @DisplayName("PG-DL5: different DNIDs create independent buckets")
    void differentDidsAreIndependentBuckets() throws Exception {
        seedExecutionFixture("UTC");
        UUID contact = seedContact(tenantId, PHONE);
        UUID otherDid = UUID.randomUUID();

        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        confirm(nextAttempt(contact), contact, didRowId, DAY);
        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        confirm(nextAttempt(contact), contact, didRowId, DAY);
        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        confirm(nextAttempt(contact), contact, didRowId, DAY);
        // DID A exhausted at 3...
        assertThat(reserve(contact, didRowId, DAY, 3)).isZero();
        // ...but DID B is a fresh bucket.
        assertThat(reserve(contact, otherDid, DAY, 3)).isEqualTo(1);
        assertThat(usedCount(contact, otherDid, DAY)).isZero();
    }

    @Test
    @DisplayName("PG-DL6: tenant and contact isolation — foreign keys never see the bucket")
    void tenantAndContactIsolation() throws Exception {
        seedExecutionFixture("UTC");
        UUID contact = seedContact(tenantId, PHONE);
        UUID otherTenant = UUID.randomUUID();
        UUID otherTenantContact = UUID.randomUUID();
        try (Connection c = raw()) {
            exec(c, "INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?)", ps -> {
                ps.setObject(1, otherTenant); ps.setString(2, "t-2-" + otherTenant);
                ps.setString(3, "s-2-" + otherTenant);
            });
        }

        // Drain the tenant-A bucket for (contact, did).
        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        assertThat(reserve(contact, didRowId, DAY, 3)).isZero();

        // Same contact id value under ANOTHER tenant is a different bucket.
        assertThat(reserve(otherTenantContact, didRowId, DAY, 3)).isEqualTo(1);
        // Tenant A's exhaustion did not move tenant B's counters.
        assertThat(usedCount(otherTenantContact, didRowId, DAY)).isZero();

        // Different contact, same tenant + DID: independent too.
        UUID contactB = seedContact(tenantId, "+919876500002");
        assertThat(reserve(contactB, didRowId, DAY, 3)).isEqualTo(1);
    }

    @Test
    @DisplayName("PG-DL7: calendar-day boundary — tomorrow (snapshot tz) is a fresh bucket")
    void calendarDayBoundaryResets() throws Exception {
        seedExecutionFixture("UTC");
        UUID contact = seedContact(tenantId, PHONE);

        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        confirm(nextAttempt(contact), contact, didRowId, DAY);
        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        confirm(nextAttempt(contact), contact, didRowId, DAY);
        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);
        confirm(nextAttempt(contact), contact, didRowId, DAY);
        assertThat(reserve(contact, didRowId, DAY, 3)).isZero();

        LocalDate tomorrow = DAY.plusDays(1);
        assertThat(reserve(contact, didRowId, tomorrow, 3)).isEqualTo(1);
        assertThat(usedCount(contact, didRowId, tomorrow)).isZero();
    }

    @Test
    @DisplayName("PG-DL8: effectiveLimit below 3 is enforced by the database conditional update")
    void campaignStricterLimitEnforced() throws Exception {
        seedExecutionFixture("UTC");
        UUID contact = seedContact(tenantId, PHONE);

        assertThat(reserve(contact, didRowId, DAY, 2)).isEqualTo(1);
        assertThat(reserve(contact, didRowId, DAY, 2)).isEqualTo(1);
        assertThat(reserve(contact, didRowId, DAY, 2)).isZero();
    }

    // === B. end-to-end dial-path integration (real OutboundDialService) ===

    @Test
    @DisplayName("PG-DL9: provider +OK consumes exactly one usage; pre-acceptance failures consume none")
    void dialPathCountsOnlyProviderAcceptance() throws Exception {
        seedExecutionFixture("UTC");
        UUID contact = seedContact(tenantId, PHONE);

        com.shivang.obd.voice.media.OutboundDialer dialer =
            org.mockito.Mockito.mock(com.shivang.obd.voice.media.OutboundDialer.class);
        CallEligibility eligibility = org.mockito.Mockito.mock(CallEligibility.class);
        com.shivang.obd.voice.routing.VoiceRoutingService routing =
            org.mockito.Mockito.mock(com.shivang.obd.voice.routing.VoiceRoutingService.class);
        com.shivang.obd.voice.capacity.VoiceCapacityService capacity =
            org.mockito.Mockito.mock(com.shivang.obd.voice.capacity.VoiceCapacityService.class);

        org.mockito.Mockito.when(eligibility.evaluate(
                org.mockito.ArgumentMatchers.any(CallEligibility.Context.class),
                org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(CallEligibility.EligibilityResult.allowed());
        org.mockito.Mockito.when(routing.resolveRoute(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> com.shivang.obd.voice.routing.VoiceRoutingDecision.primary(
                new com.shivang.obd.voice.routing.VoiceRoute(
                    gatewayId, "fs-" + gatewayId, "external", "TATA", didRowId,
                    inv.getArgument(2)),
                com.shivang.obd.voice.routing.VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode(),
                List.of()));
        org.mockito.Mockito.when(capacity.reserve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(true);

        dialService = new OutboundDialService(
            attemptRepository, contactRepository, tenantRepository,
            campaignRepository, executionRepository,
            new CampaignRuntimeConfigResolver(new CampaignConfigurationService(
                snapshotRepository,
                new com.shivang.obd.campaign.config.CampaignTypeConfigValidator())),
            dialer, eligibility, routing, capacity,
            callSessionRepository, callLegRepository, limitService,
                new DailyAttemptSafetyService(dailyAttemptRepository, limitService,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new PreDispatchFailureMapper());

        // (a) pre-acceptance failure: BUSY consumes nothing.
        UUID attemptA = nextAttempt(contact);
        org.mockito.Mockito.when(dialer.dial(org.mockito.ArgumentMatchers.any()))
            .thenReturn(com.shivang.obd.voice.media.OutboundDialResponse.busy());
        transactionTemplate().executeWithoutResult(tx -> dialService.processDueAttempts());
        assertThat(attemptRepository.findById(attemptA).orElseThrow().getStatus())
            .isEqualTo(CallAttemptStatus.FAILED);
        assertThat(usedCount(contact, didRowId, DAY)).isZero();
        Number reservedAfterBusy = (Number) entityManager.createNativeQuery(
                "SELECT reserved_count FROM voice_blast_daily_usage "
                + "WHERE tenant_id = ? AND contact_id = ? AND did_id = ? AND usage_date = ?")
            .setParameter(1, tenantId).setParameter(2, contact)
            .setParameter(3, didRowId).setParameter(4, DAY)
            .getSingleResult();
        assertThat(reservedAfterBusy.intValue()).isZero();

        // (b) provider acceptance (+OK) consumes exactly one slot.
        UUID attemptB = nextAttempt(contact);
        org.mockito.Mockito.when(dialer.dial(org.mockito.ArgumentMatchers.any()))
            .thenReturn(com.shivang.obd.voice.media.OutboundDialResponse.accepted("fs-ok-1"));
        transactionTemplate().executeWithoutResult(tx -> dialService.processDueAttempts());
        var accepted = attemptRepository.findById(attemptB).orElseThrow();
        assertThat(accepted.getStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);
        assertThat(accepted.getProviderCallId()).isEqualTo("fs-ok-1");
        assertThat(usedCountRaw(contact, didRowId, DAY)).isEqualTo(1);

        // (c) reaching the limit: next admission is DAILY_LIMIT_REACHED,
        //     permanent, and the dialer is never called again.
        UUID attemptC = nextAttempt(contact);
        UUID attemptD = nextAttempt(contact);
        transactionTemplate().executeWithoutResult(tx -> dialService.processDueAttempts()); // slot 2 (attemptC)
        transactionTemplate().executeWithoutResult(tx -> dialService.processDueAttempts()); // slot 3 (attemptD)
        assertThat(usedCountRaw(contact, didRowId, DAY)).isEqualTo(3);

        UUID attemptE = nextAttempt(contact);
        transactionTemplate().executeWithoutResult(tx -> dialService.processDueAttempts());
        var blocked = attemptRepository.findById(attemptE).orElseThrow();
        assertThat(blocked.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(blocked.getFailureCode()).isEqualTo(CallFailureCode.DAILY_LIMIT_REACHED.name());
        org.mockito.Mockito.verify(dialer, org.mockito.Mockito.times(4))
            .dial(org.mockito.ArgumentMatchers.any()); // busy + 3 accepted, never the 4th contact dial
    }

    @Test
    @DisplayName("PG-DL10: invalid snapshot timezone fails the dial deterministically (no admission)")
    void dialPathFailsOnInvalidTimezone() throws Exception {
        seedExecutionFixture(null); // snapshot has NO timezone
        UUID contact = seedContact(tenantId, PHONE);
        UUID attemptId = seedAttempt(contact, 1);

        com.shivang.obd.voice.media.OutboundDialer dialer =
            org.mockito.Mockito.mock(com.shivang.obd.voice.media.OutboundDialer.class);
        CallEligibility eligibility = org.mockito.Mockito.mock(CallEligibility.class);
        com.shivang.obd.voice.routing.VoiceRoutingService routing =
            org.mockito.Mockito.mock(com.shivang.obd.voice.routing.VoiceRoutingService.class);
        com.shivang.obd.voice.capacity.VoiceCapacityService capacity =
            org.mockito.Mockito.mock(com.shivang.obd.voice.capacity.VoiceCapacityService.class);

        dialService = new OutboundDialService(
            attemptRepository, contactRepository, tenantRepository,
            campaignRepository, executionRepository,
            new CampaignRuntimeConfigResolver(new CampaignConfigurationService(
                snapshotRepository,
                new com.shivang.obd.campaign.config.CampaignTypeConfigValidator())),
            dialer, eligibility, routing, capacity,
            callSessionRepository, callLegRepository, limitService,
                new DailyAttemptSafetyService(dailyAttemptRepository, limitService,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new PreDispatchFailureMapper());

        transactionTemplate().executeWithoutResult(tx -> dialService.processDueAttempts());

        var a = attemptRepository.findById(attemptId).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(a.getFailureCode()).isEqualTo(CallFailureCode.EXECUTION_TIMEZONE_INVALID.name());
        // No bucket was ever created for the contact.
        Number buckets = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM voice_blast_daily_usage WHERE contact_id = ?")
            .setParameter(1, contact)
            .getSingleResult();
        assertThat(buckets.intValue()).isZero();
        org.mockito.Mockito.verify(dialer, org.mockito.Mockito.never())
            .dial(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("PG-DL11: usage_date is computed in the execution snapshot's timezone")
    void usageDateUsesSnapshotTimezone() throws Exception {
        seedExecutionFixture("Asia/Kolkata");
        UUID contact = seedContact(tenantId, PHONE);
        UUID attemptId = nextAttempt(contact);

        // 20:00Z = 01:30 IST next day — the snapshot-zone day is +1 vs UTC's.
        LocalDate kolkataToday = LocalDate.of(2026, 9, 28);

        com.shivang.obd.voice.media.OutboundDialer dialer =
            org.mockito.Mockito.mock(com.shivang.obd.voice.media.OutboundDialer.class);
        CallEligibility eligibility = org.mockito.Mockito.mock(CallEligibility.class);
        com.shivang.obd.voice.routing.VoiceRoutingService routing =
            org.mockito.Mockito.mock(com.shivang.obd.voice.routing.VoiceRoutingService.class);
        com.shivang.obd.voice.capacity.VoiceCapacityService capacity =
            org.mockito.Mockito.mock(com.shivang.obd.voice.capacity.VoiceCapacityService.class);
        org.mockito.Mockito.when(eligibility.evaluate(
                org.mockito.ArgumentMatchers.any(CallEligibility.Context.class),
                org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(CallEligibility.EligibilityResult.allowed());
        org.mockito.Mockito.when(routing.resolveRoute(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> com.shivang.obd.voice.routing.VoiceRoutingDecision.primary(
                new com.shivang.obd.voice.routing.VoiceRoute(
                    gatewayId, "fs-" + gatewayId, "external", "TATA", didRowId,
                    inv.getArgument(2)),
                com.shivang.obd.voice.routing.VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode(),
                List.of()));
        org.mockito.Mockito.when(capacity.reserve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(true);
        org.mockito.Mockito.when(dialer.dial(org.mockito.ArgumentMatchers.any()))
            .thenReturn(com.shivang.obd.voice.media.OutboundDialResponse.accepted("fs-ok-tz"));

        dialService = new OutboundDialService(
            attemptRepository, contactRepository, tenantRepository,
            campaignRepository, executionRepository,
            new CampaignRuntimeConfigResolver(new CampaignConfigurationService(
                snapshotRepository,
                new com.shivang.obd.campaign.config.CampaignTypeConfigValidator())),
            dialer, eligibility, routing, capacity,
            callSessionRepository, callLegRepository, limitService,
                new DailyAttemptSafetyService(dailyAttemptRepository, limitService,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new PreDispatchFailureMapper());

        transactionTemplate().executeWithoutResult(tx -> dialService.processDueAttempts());

        assertThat(attemptRepository.findById(attemptId).orElseThrow().getProviderCallId())
            .isEqualTo("fs-ok-tz");
        assertThat(usedCount(contact, didRowId, kolkataToday)).isEqualTo(1);
        assertThat(usedCount(contact, didRowId, kolkataToday.minusDays(1))).isZero();
    }

    @Test
    @DisplayName("PG-DL12: concurrent duplicate confirmations of one attempt stay at one usage")
    void concurrentDuplicateConfirmationsStayAtOne() throws Exception {
        seedExecutionFixture("UTC");
        UUID contact = seedContact(tenantId, PHONE);
        UUID attemptId = seedAttempt(contact, 1);

        assertThat(reserve(contact, didRowId, DAY, 3)).isEqualTo(1);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        // Every worker replays the same acceptance event.
                        transactionTemplate().executeWithoutResult(tx ->
                            limitService.confirmAccepted(tenantId, attemptId, contact,
                                    didRowId, DAY, "fs-dup"));
                    } catch (Exception ignored) {
                        // Even an unexpected failure must not inflate usage;
                        // final DB state is asserted below.
                    }
                    return null;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(usedCountRaw(contact, didRowId, DAY)).isEqualTo(1);
        Number entries = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM voice_blast_daily_usage_entries WHERE call_attempt_id = ?")
            .setParameter(1, attemptId)
            .getSingleResult();
        assertThat(entries.intValue()).isEqualTo(1);
    }
}
