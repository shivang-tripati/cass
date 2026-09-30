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
import java.time.LocalTime;
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
 * VB-8J — calling-window enforcement and the duplicate-execution policy, against
 * real PostgreSQL.
 *
 * <p>VB-8I found the window was honoured only when {@code scheduledAt} was first
 * computed, so a 09:00-19:00 campaign dialled at 19:01 and at 03:00. These tests
 * pin the replacement at the real dispatch decision point, plus the C-7 guard
 * against two executions dialling the same contact twice.
 *
 * <p>Windows here are built relative to {@code Instant.now()} so they are
 * clock-robust: a window that starts an hour from now is closed, one that ended
 * an hour ago is closed, and a 00:00-23:59:59 window is open.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallingWindowAndDuplicateExecutionPostgresIntegrationTest {

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
    private OutboundDialService dialService;
    private com.shivang.obd.voice.media.OutboundDialer dialer;
    private com.shivang.obd.voice.capacity.VoiceCapacityService capacity;
    private com.shivang.obd.voice.routing.VoiceRoutingService routing;
    private CallEligibility eligibility;
    private DailyDialLimitService limitService;
    private DailyAttemptSafetyService attemptSafety;
    private UUID lastDid;
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
        runtimeConfigResolver = new CampaignRuntimeConfigResolver(
                new CampaignConfigurationService(
                        snapshotRepository, new CampaignTypeConfigValidator()));

        dialer = org.mockito.Mockito.mock(com.shivang.obd.voice.media.OutboundDialer.class);
        capacity = org.mockito.Mockito.mock(
                com.shivang.obd.voice.capacity.VoiceCapacityService.class);
        routing = org.mockito.Mockito.mock(com.shivang.obd.voice.routing.VoiceRoutingService.class);
        eligibility = org.mockito.Mockito.mock(CallEligibility.class);
        limitService = org.mockito.Mockito.mock(DailyDialLimitService.class);
        attemptSafety = org.mockito.Mockito.mock(DailyAttemptSafetyService.class);

        lenient().when(eligibility.evaluate(any(CallEligibility.Context.class), any()))
                .thenReturn(CallEligibility.EligibilityResult.allowed());
        lenient().when(attemptSafety.admit(any(), any(), any(), any()))
                .thenReturn(DailyAttemptSafetyService.AdmissionResult.ADMITTED);
        lenient().when(limitService.admit(any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(DailyDialLimitService.AdmissionResult.ADMITTED);
        lenient().when(limitService.resolveUsageDate(any()))
                .thenReturn(java.time.LocalDate.of(2026, 9, 29));
        lenient().when(limitService.effectiveLimit(any())).thenReturn(3);
        lenient().when(capacity.reserve(any(), any())).thenReturn(true);
        lenient().when(dialer.dial(any())).thenAnswer(inv -> {
            dialCount.incrementAndGet();
            return com.shivang.obd.voice.media.OutboundDialResponse.accepted("prov-x");
        });
        stubRoute();
        dialService = new OutboundDialService(
                attemptRepository, contactRepository, tenantRepository, campaignRepository,
                executionRepository, runtimeConfigResolver, dialer, eligibility, routing,
                capacity, callSessionRepository, callLegRepository, limitService,
                attemptSafety, new PreDispatchFailureMapper(), tx,
                new ExecutionScheduleCalculator());
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

    // =====================================================================
    // C-4 calling-window enforcement
    // =====================================================================

    @Test
    @DisplayName("C4: a closed window prevents dispatch and keeps the attempt QUEUED")
    void closedWindowPreventsDispatch() {
        Fixture f = seedFixture("closed", windowStartingIn(2));
        CallAttempt attempt = seedAttempt(f);

        dialService.processDueAttempts();

        assertThat(dialCount.get())
                .as("FreeSWITCH must not be reached outside the calling window")
                .isZero();
        assertThat(reload(attempt.getId()).getStatus())
                .as("the attempt stays unfinished, never failed by window closure")
                .isEqualTo(CallAttemptStatus.QUEUED);
    }

    @Test
    @DisplayName("C4: a window that already closed today also prevents dispatch")
    void expiredWindowForTodayPreventsDispatch() {
        Fixture f = seedFixture("expired", windowEndingIn(-2));
        CallAttempt attempt = seedAttempt(f);

        dialService.processDueAttempts();

        assertThat(dialCount.get()).isZero();
        assertThat(reload(attempt.getId()).getStatus())
                .isEqualTo(CallAttemptStatus.QUEUED);
    }

    @Test
    @DisplayName("C4: window deferral consumes no dial, attempt or capacity budget")
    void windowDeferralConsumesNoBudget() {
        Fixture f = seedFixture("nobudget", windowStartingIn(2));
        seedAttempt(f);
        org.mockito.Mockito.clearInvocations(limitService, attemptSafety, capacity);

        dialService.processDueAttempts();

        org.mockito.Mockito.verifyNoInteractions(limitService, attemptSafety, capacity);
    }

    @Test
    @DisplayName("C4: deferral pushes scheduledAt forward so it resumes, without churning")
    void deferralPushesScheduledAtForward() {
        Fixture f = seedFixture("defer", windowStartingIn(2));
        CallAttempt attempt = seedAttempt(f);
        Instant before = reload(attempt.getId()).getScheduledAt();

        dialService.processDueAttempts();

        Instant after = reload(attempt.getId()).getScheduledAt();
        assertThat(after)
                .as("the next window opening, so the next tick does not reselect it")
                .isAfter(before);
        assertThat(after).isAfter(Instant.now());

        int processed = dialService.processDueAttempts();
        assertThat(processed)
                .as("and immediately after, the attempt is not due again (no hot loop)")
                .isZero();
    }

    @Test
    @DisplayName("C4: an all-day window still dispatches (unchanged behaviour)")
    void openWindowDispatches() {
        Fixture f = seedFixture("open", null);
        CallAttempt attempt = seedAttempt(f);

        dialService.processDueAttempts();

        assertThat(dialCount.get()).isEqualTo(1);
        assertThat(reload(attempt.getId()).getStatus())
                .isEqualTo(CallAttemptStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("C4: window closure never completes the execution")
    void windowClosureNeverCompletesTheExecution() {
        Fixture f = seedFixture("notcomplete", windowStartingIn(2));
        CallAttempt attempt = seedAttempt(f);
        setExecutionStatus(f.executionId(), CampaignExecutionStatus.RUNNING);

        dialService.processDueAttempts();

        assertThat(executionStatus(f.executionId()))
                .as("an unfinished execution stays RUNNING across a closed window")
                .isEqualTo(CampaignExecutionStatus.RUNNING);
        assertThat(attempt.getId()).isNotNull();
    }

    @Test
    @DisplayName("C4: a claimed attempt already in progress is never gated by the window")
    void inProgressAttemptIsNotGated() {
        // The window is closed, but this attempt is already dispatched. Window
        // closure must not touch established work - that is the invariant that
        // keeps active calls safe.
        Fixture f = seedFixture("inprogress", windowStartingIn(2));
        CallAttempt attempt = seedAttempt(f);
        int claimed = tx.execute(t -> attemptRepository.claimForDispatch(
                attempt.getId(), f.tenantId(),
                CallAttemptStatus.QUEUED, CallAttemptStatus.IN_PROGRESS, Instant.now()));
        assertThat(claimed).isEqualTo(1);

        dialService.processDueAttempts();

        assertThat(reload(attempt.getId()).getStatus())
                .as("already dispatched work is left alone")
                .isEqualTo(CallAttemptStatus.IN_PROGRESS);
        assertThat(dialCount.get()).isZero();
    }

    // =====================================================================
    // C-7 duplicate / concurrent execution policy
    // =====================================================================

    @Test
    @DisplayName("C7: a second execution for a campaign with one in flight is refused")
    void secondExecutionIsRefused() {
        Fixture f = seedFixture("dup", null);
        // The fixture seeds an in-flight execution, so the first *new* execution
        // is already the duplicate under test.
        assertThat(tryCreateExecution(f))
                .as("a campaign with a RUNNING execution accepts no second one")
                .isFalse();
        assertThat(executionCount(f.campaignId())).isEqualTo(1);
    }

    @Test
    @DisplayName("C7: a new execution is allowed once the previous one is terminal")
    void newExecutionAllowedAfterTerminal() {
        Fixture f = seedFixture("seq", null);
        setExecutionStatus(f.executionId(), CampaignExecutionStatus.COMPLETED);

        assertThat(tryCreateExecution(f))
                .as("a finished run does not block the next one")
                .isTrue();
        assertThat(executionCount(f.campaignId())).isEqualTo(2);
    }

    @Test
    @DisplayName("C7: concurrent creation of the same campaign yields exactly one execution")
    void concurrentCreationYieldsOneExecution() throws Exception {
        Fixture f = seedFixture("race", null);
        setExecutionStatus(f.executionId(), CampaignExecutionStatus.COMPLETED);
        int workers = 6;
        AtomicInteger created = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            for (int i = 0; i < workers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(20, TimeUnit.SECONDS);
                        if (tryCreateExecution(f)) {
                            created.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // asserted through persisted state
                    }
                });
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(created.get())
                .as("the row lock serialises creators; losers see the committed execution")
                .isEqualTo(1);
        assertThat(executionCount(f.campaignId()))
                .as("the seeded terminal execution plus exactly one new one")
                .isEqualTo(2);
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private void stubRoute() {
        var route = org.mockito.Mockito.mock(com.shivang.obd.voice.routing.VoiceRoute.class);
        lenient().when(route.didId()).thenReturn(lastDidHolder());
        lenient().when(route.didE164Number()).thenReturn("+919700000001");
        lenient().when(route.freeSwitchGatewayName()).thenReturn("fs-a");
        lenient().when(route.freeSwitchProfile()).thenReturn("sofia/p1");
        lenient().when(route.provider()).thenReturn("TATA");
        lenient().when(route.gatewayId()).thenReturn(null);
        lenient().when(routing.resolveRoute(any(), any(), any(), any(), any(), any()))
                .thenReturn(com.shivang.obd.voice.routing.VoiceRoutingDecision.primary(
                        route, "selected", java.util.List.of()));
    }

    private UUID lastDidHolder() {
        return lastDid == null ? UUID.randomUUID() : lastDid;
    }

    /** @param startOffsetHours null = all-day window, else opens that many hours from now */
    private ScheduleSpec windowStartingIn(Integer startOffsetHours) {
        ScheduleSpec s = new ScheduleSpec();
        s.setTimezone("UTC");
        if (startOffsetHours == null) {
            s.setStartTime(LocalTime.MIDNIGHT);
            s.setEndTime(LocalTime.of(23, 59, 59));
        } else if (startOffsetHours > 0) {
            LocalTime start = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                    .plusHours(startOffsetHours).toLocalTime();
            s.setStartTime(start);
            s.setEndTime(start.plusHours(1));
        } else {
            LocalTime end = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                    .plusHours(startOffsetHours).toLocalTime();
            s.setStartTime(end.minusHours(1));
            s.setEndTime(end);
        }
        return s;
    }

    private ScheduleSpec windowEndingIn(int hoursAgo) {
        return windowStartingIn(-Math.abs(hoursAgo));
    }

    private boolean tryCreateExecution(Fixture f) {
        try {
            createExecution(f);
            return true;
        } catch (RuntimeException refused) {
            return false;
        }
    }

    private UUID createExecution(Fixture f) {
        return tx.execute(t -> {
            int locked = executionRepository.lockCampaignRow(f.campaignId(), f.tenantId());
            if (locked == 0) {
                throw new IllegalStateException("campaign row not lockable");
            }
            if (executionRepository.existsActiveByCampaignId(f.campaignId())) {
                throw new IllegalStateException("campaign already has an active execution");
            }
            var execution = new CampaignExecution();
            execution.setCampaignId(f.campaignId());
            execution.setTenantId(f.tenantId());
            execution.setConfigurationSnapshotId(snapshotId(f));
            execution.setStatus(CampaignExecutionStatus.REQUESTED);
            execution.setRequestedAt(Instant.now());
            execution.setRequestedBy(CALLER_ID.toString());
            return executionRepository.saveAndFlush(execution).getId();
        });
    }

    private UUID snapshotId(Fixture f) {
        return tx.execute(t -> executionRepository
                .findByCampaignIdAndTenantIdAndDeletedAtIsNullOrderByRequestedAtDesc(
                        f.campaignId(), f.tenantId()).stream()
                .findFirst().map(CampaignExecution::getConfigurationSnapshotId)
                .orElse(null));
    }

    private int executionCount(UUID campaignId) {
        Long n = tx.execute(t -> entityManager.createQuery(
                "SELECT COUNT(e) FROM CampaignExecution e WHERE e.campaignId = :c", Long.class)
                .setParameter("c", campaignId).getSingleResult());
        return n == null ? 0 : n.intValue();
    }

    private CallAttempt reload(UUID id) {
        return tx.execute(t -> attemptRepository.findByIdAndDeletedAtIsNull(id)).orElseThrow();
    }

    private CampaignExecutionStatus executionStatus(UUID id) {
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
            a.setScheduledAt(Instant.now().minusSeconds(60));
            return attemptRepository.saveAndFlush(a);
        });
    }

    private record Fixture(UUID campaignId, UUID executionId, UUID contactId,
                           UUID tenantId, UUID didId) {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final UUID CALLER_ID =
            UUID.fromString("ff000000-0000-4000-8000-0000000000fb");

    private Fixture seedFixture(String label, ScheduleSpec schedule) {
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
            c.setName("c-vb8j-" + n);
            c.setCampaignType(CampaignType.PLAYFILE);
            c.setStatus(CampaignStatus.SCHEDULED);
            c.setContentMode(ContentMode.AUDIO);
            c.setAudioAssetId(seedAudio(tenantId));
            c.setDidId(didId);
            c.setContactGroupId(groupId);
            c.setSchedule(schedule);
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
            a.setStorageReference("s3://vb8j/" + n + ".wav");
            a.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
            return audioAssetRepository.saveAndFlush(a).getId();
        });
    }
}
