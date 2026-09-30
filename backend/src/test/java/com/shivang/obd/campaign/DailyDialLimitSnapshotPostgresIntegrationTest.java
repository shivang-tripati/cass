package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.dto.ExecuteCampaignRequest;
import com.shivang.obd.campaign.dto.ScheduleConfig;
import com.shivang.obd.campaign.dto.RetryPolicyConfig;
import com.shivang.obd.campaign.dto.CreateCampaignRequest;
import com.shivang.obd.campaign.dto.UpdateCampaignRequest;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.contact.ContactGroupEntity;
import com.shivang.obd.contact.ContactGroupRepository;
import com.shivang.obd.did.AllocationSource;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.did.NumberType;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.audio.AudioAssetEntity;
import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.audio.AudioAssetStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-6C.2 — the campaign dailyDialLimit against real PostgreSQL (full
 * Flyway chain incl. V48), through the REAL service stack:
 * {@code CampaignService} → {@code CampaignExecutionService} →
 * {@code CampaignConfigurationService} → {@code CampaignRuntimeConfigResolver}.
 *
 * <ul>
 *   <li>THE mandatory snapshot-immutability scenario: campaign 3 →
 *       execution E1 (snapshot 3) → campaign changed to 1 → E1 still 3,
 *       new execution E2 = 1; runtime resolution E1 → effectiveLimit 3,
 *       E2 → effectiveLimit 1.</li>
 *   <li>Null-default semantics: null persists as null; the runtime
 *       computes effectiveLimit(null) = 3.</li>
 *   <li>DB CHECK constraint rejects 0/-1/4 even against raw writes.</li>
 *   <li>Clean-database migration chain V1..V48 applies (this whole suite
 *       runs it on a fresh container).</li>
 * </ul>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DailyDialLimitSnapshotPostgresIntegrationTest {

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
    static void registerProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
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
    private TenantRepository tenantRepository;
    @Autowired
    private ContactGroupRepository contactGroupRepository;
    @Autowired
    private ContactGroupRepository groupRepository;
    @Autowired
    private DidRepository didRepository;
    @Autowired
    private AudioAssetRepository audioAssetRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private static final UUID CALLER_ID =
        UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final AtomicInteger SEQ = new AtomicInteger(100);

    private org.springframework.transaction.support.TransactionTemplate tx() {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    private CampaignService campaignService;
    private CampaignExecutionService executionService;
    private CampaignRuntimeConfigResolver runtimeConfigResolver;

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                    + startupFailure.getMessage(), startupFailure);
        }
        com.shivang.obd.authz.AuthorizationService allowAll =
            new com.shivang.obd.authz.AuthorizationService(
                List.of(), null, null, null, List.of()) {
                @Override
                public void requireCapability(UUID userId, String capabilityKey,
                    com.shivang.obd.authz.AccessCheck target) {
                }
            };
        CurrentUserProvider currentUser = new CurrentUserProvider() {
            @Override
            public java.util.Optional<AuthenticatedUser> current() {
                return java.util.Optional.of(new AuthenticatedUser(CALLER_ID, "it@test.local", null));
            }
        };
        CampaignConfigurationService configurationService = new CampaignConfigurationService(
            snapshotRepository,
            new com.shivang.obd.campaign.config.CampaignTypeConfigValidator());
        runtimeConfigResolver = new CampaignRuntimeConfigResolver(configurationService);
        CampaignReadinessService readinessService = new CampaignReadinessService(
            campaignRepository, allowAll, currentUser,
            contactGroupRepository,
            new CampaignResourceValidationService(didRepository, audioAssetRepository, null),
            tenantRepository);
        // Real CampaignService with a real (context-publisher-backed) event
        // publisher — no mocking of the service under test.
        com.shivang.obd.campaign.event.CampaignEventPublisher eventPublisher =
            new com.shivang.obd.campaign.event.CampaignEventPublisher(
                new org.springframework.context.support.StaticApplicationContext());
        campaignService = new CampaignService(
            campaignRepository, allowAll, eventPublisher, currentUser,
            new CampaignMapper(), tenantRepository, contactGroupRepository,
            new CampaignResourceValidationService(didRepository, audioAssetRepository, null),
            new CampaignLifecyclePolicy());
        executionService = new CampaignExecutionService(
            campaignRepository, executionRepository, allowAll, currentUser,
            readinessService, tenantRepository, configurationService);
        com.shivang.obd.authz.context.OrganizationContextHolder
            .setAuthenticated(CALLER_ID, null, null);
    }

    @AfterEach
    void clearContext() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    private UUID seedTenant() {
        return tx().execute(status -> {
            TenantEntity t = new TenantEntity();
            t.setName("tenant-" + SEQ.incrementAndGet());
            t.setSlug("t-" + SEQ.incrementAndGet());
            t.setStatus(com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE);
            return tenantRepository.saveAndFlush(t).getId();
        });
    }

    private UUID seedDid(UUID tenantId) {
        return tx().execute(t -> {
            DidEntity d = new DidEntity();
            d.setE164Number("+9199" + String.format("%08d", SEQ.incrementAndGet()));
            d.setCountryCode("+91");
            d.setNumberType(NumberType.MOBILE);
            d.setProvider("TATA");
            d.setStatus(DidStatus.ACTIVE);
            d.setAllocationState(AllocationState.ASSIGNED);
            d.setAllocationSource(AllocationSource.PLATFORM);
            d.setTenantId(tenantId);
            return didRepository.saveAndFlush(d).getId();
        });
    }

    private UUID seedAudio(UUID tenantId) {
        return tx().execute(t -> {
            AudioAssetEntity a = new AudioAssetEntity();
            a.setTenantId(tenantId);
            a.setName("asset-" + SEQ.incrementAndGet());
            a.setFileName("a-" + SEQ.get() + ".wav");
            a.setContentType("audio/wav");
            a.setFileSize(1024L);
            a.setStorageReference("s3://dll/" + SEQ.incrementAndGet() + ".wav");
            a.setStatus(AudioAssetStatus.APPROVED);
            return audioAssetRepository.saveAndFlush(a).getId();
        });
    }

    /**
     * Real service-stack campaign creation (create → mapper → validation →
     * save), then the real DRAFT → SCHEDULED transition so the campaign is
     * actually executable. Both steps go through the genuine service stack:
     * creation legitimately lands in DRAFT, and {@code CampaignReadiness
     * Service} only permits SCHEDULED/RUNNING to execute, so the transition
     * is part of the scenario rather than a shortcut around it (it also
     * re-validates the configuration via {@code validateActivation}).
     */
    private UUID createCampaign(UUID tenantId, Integer dailyDialLimit) {
        com.shivang.obd.authz.context.OrganizationContextHolder
            .setAuthenticated(CALLER_ID, tenantId, null);
        CreateCampaignRequest request = new CreateCampaignRequest(
            "c-dll-" + SEQ.incrementAndGet(), null, CampaignType.PLAYFILE, null,
            null, seedDid(tenantId),
            ContentMode.AUDIO, seedAudio(tenantId), null,
            new ScheduleConfig(
                LocalDate.of(2026, 9, 1),
                // A FULL-DAY window, deliberately. A bounded 09:00-18:00 window
                // made execution readiness depend on the wall-clock time the
                // suite happened to run at: this test asserts the frozen
                // dailyDialLimit, not the calling window, so binding it to a
                // clock turns an unrelated assertion into a time bomb (it
                // started failing after 18:00 IST). Tests that DO assert
                // schedule readiness keep their bounded window on purpose.
                LocalTime.of(0, 0), LocalTime.of(23, 59), "Asia/Kolkata", Set.of(), null),
            new RetryPolicyConfig(0, null, null),
            null, null, false,
            dailyDialLimit);
        UUID campaignId = campaignService.create(request, null).data().id();
        changeStatus(tenantId, campaignId, "SCHEDULED");
        return campaignId;
    }

    /** The real lifecycle transition (DRAFT → SCHEDULED, SCHEDULED → DRAFT). */
    private void changeStatus(UUID tenantId, UUID campaignId, String status) {
        com.shivang.obd.authz.context.OrganizationContextHolder
            .setAuthenticated(CALLER_ID, tenantId, null);
        tx().executeWithoutResult(t -> campaignService.changeStatus(
            campaignId, new com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest(status)));
    }

    /** Real service-stack execution creation (readiness → snapshot → execution). */
    private UUID createExecution(UUID tenantId, UUID campaignId) {
        com.shivang.obd.authz.context.OrganizationContextHolder
            .setAuthenticated(CALLER_ID, tenantId, null);
        return executionService
            .execute(campaignId, new ExecuteCampaignRequest(null))
            .data().id();
    }

    private Integer snapshotLimit(UUID executionId) {
        return tx().execute(t -> {
            CampaignExecution execution = executionRepository
                .findByIdAndDeletedAtIsNull(executionId).orElseThrow();
            return runtimeConfigResolver.resolve(execution).dailyDialLimit();
        });
    }

    private int effectiveLimitOf(UUID executionId) {
        return tx().execute(t -> {
            CampaignExecution execution = executionRepository
                .findByIdAndDeletedAtIsNull(executionId).orElseThrow();
            return new DailyDialLimitService(
                org.mockito.Mockito.mock(VoiceBlastDailyUsageRepository.class),
                org.mockito.Mockito.mock(VoiceBlastDailyUsageEntryRepository.class),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
                .effectiveLimit(runtimeConfigResolver.resolve(execution).dailyDialLimit());
        });
    }

    private Integer campaignLimit(UUID campaignId) {
        return tx().execute(t ->
            entityManager.find(CampaignEntity.class, campaignId).getDailyDialLimit());
    }

    /**
     * Changes the campaign's configured limit through the REAL update path
     * (mapper → {@code assertEditable} → {@code assertConfigurable} → save),
     * exactly as a PUT would. The caller must have unlocked the campaign
     * (SCHEDULED → DRAFT) first, because VB-6A editability permits
     * configuration changes only in DRAFT — the test deliberately exercises
     * the real lifecycle gate instead of writing the column behind the
     * service's back.
     */
    private void updateCampaignLimit(UUID tenantId, UUID campaignId, Integer newLimit) {
        com.shivang.obd.authz.context.OrganizationContextHolder
            .setAuthenticated(CALLER_ID, tenantId, null);
        tx().executeWithoutResult(t -> {
            CampaignEntity current = campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            ScheduleSpec schedule = current.getSchedule();
            RetryPolicySpec retry = current.getRetryPolicy();
            // PUT semantics: the whole configuration block is replaced, so
            // every field is echoed back and only the limit differs.
            UpdateCampaignRequest request = new UpdateCampaignRequest(
                current.getName(), current.getDescription(), current.getRunMode(),
                current.getContactGroupId(), current.getDidId(),
                current.getContentMode(), current.getAudioAssetId(),
                current.getTtsTemplateId(),
                new ScheduleConfig(
                    schedule.getStartDate(),
                    schedule.getStartTime(), schedule.getEndTime(),
                    schedule.getTimezone(), schedule.getAllowedDaysOfWeek(),
                    schedule.getHolidayCalendarId()),
                new RetryPolicyConfig(
                    retry.getMaxAttempts(), retry.getIntervalSeconds(),
                    retry.getStrategy()),
                null, null,
                newLimit);
            campaignService.update(campaignId, request);
        });
    }

    @Test
    @DisplayName("PG-6C2-1: snapshot immutability — campaign edits never change an existing execution")
    void snapshotImmutability() {
        UUID tenantId = seedTenant();
        UUID campaignId = createCampaign(tenantId, 3);
        assertThat(campaignLimit(campaignId)).isEqualTo(3);

        UUID e1 = createExecution(tenantId, campaignId);
        assertThat(snapshotLimit(e1)).isEqualTo(3);
        assertThat(effectiveLimitOf(e1)).isEqualTo(3);

        // The campaign is SCHEDULED and therefore NOT editable. VB-6A unlocks
        // it with the explicit SCHEDULED -> DRAFT transition, then the limit
        // is changed through the real PUT path.
        changeStatus(tenantId, campaignId, "DRAFT");
        updateCampaignLimit(tenantId, campaignId, 1);
        assertThat(campaignLimit(campaignId)).isEqualTo(1);
        changeStatus(tenantId, campaignId, "SCHEDULED");

        // E1's frozen snapshot still carries 3, resolved as effective 3.
        assertThat(snapshotLimit(e1)).isEqualTo(3);
        assertThat(effectiveLimitOf(e1)).isEqualTo(3);

        // A NEW execution freezes the new value.
        settle(e1);

        UUID e2 = createExecution(tenantId, campaignId);
        assertThat(snapshotLimit(e2)).isEqualTo(1);
        assertThat(effectiveLimitOf(e2)).isEqualTo(1);
    }

    /**
     * VB-8J: settles an execution so a later one may be created.
     *
     * The product allows one in-flight execution per campaign, because two
     * concurrent executions would materialise an attempt for every contact in
     * the audience twice. Snapshot immutability does not depend on the pair
     * being concurrent: what it asserts is that the frozen row is never
     * rewritten, which a sequential pair establishes just as well.
     */
    private void settle(UUID executionId) {
        tx().executeWithoutResult(t -> {
            CampaignExecution e = executionRepository
                    .findByIdAndDeletedAtIsNull(executionId).orElseThrow();
            e.setStatus(CampaignExecutionStatus.COMPLETED);
            executionRepository.saveAndFlush(e);
        });
    }

    @Test
    @DisplayName("PG-6C2-2: null persists as null and resolves to the platform maximum 3")
    void nullDefaultSemantics() {
        UUID tenantId = seedTenant();
        UUID campaignId = createCampaign(tenantId, null);
        assertThat(campaignLimit(campaignId)).isNull();

        UUID executionId = createExecution(tenantId, campaignId);
        // The snapshot preserves the REQUESTED configuration (null)...
        assertThat(snapshotLimit(executionId)).isNull();
        // ...and the runtime computes the EFFECTIVE policy value.
        assertThat(effectiveLimitOf(executionId)).isEqualTo(3);
    }

    @Test
    @DisplayName("PG-6C2-3: configured 1 and 2 freeze into snapshots and resolve as stricter limits")
    void stricterLimitsSnapshotAndResolve() {
        UUID tenantId = seedTenant();

        UUID c1 = createCampaign(tenantId, 1);
        UUID e1 = createExecution(tenantId, c1);
        assertThat(snapshotLimit(e1)).isEqualTo(1);
        assertThat(effectiveLimitOf(e1)).isEqualTo(1);

        UUID c2 = createCampaign(tenantId, 2);
        UUID e2 = createExecution(tenantId, c2);
        assertThat(snapshotLimit(e2)).isEqualTo(2);
        assertThat(effectiveLimitOf(e2)).isEqualTo(2);
    }

    @Test
    @DisplayName("PG-6C2-4: V48 CHECK constraint rejects 0, -1 and 4 at the database boundary")
    void databaseCheckRejectsInvalidValues() {
        UUID tenantId = seedTenant();
        UUID campaignId = createCampaign(tenantId, 2);

        for (int invalid : new int[] {0, -1, 4}) {
            assertRejectedByCheck(
                    () -> tx().executeWithoutResult(t ->
                            entityManager.createNativeQuery(
                                    "UPDATE campaigns SET daily_dial_limit = ? WHERE id = ?")
                                .setParameter(1, (short) invalid)
                                .setParameter(2, campaignId)
                                .executeUpdate()),
                    "ck_campaigns_daily_dial_limit",
                    "campaigns.daily_dial_limit=" + invalid);
        }

        // The same invariant is frozen into the execution-snapshot table, so
        // an invalid value can never enter the immutable configuration either.
        UUID executionId = createExecution(tenantId, campaignId);
        for (int invalid : new int[] {0, -1, 4}) {
            assertRejectedByCheck(
                    () -> tx().executeWithoutResult(t ->
                            entityManager.createNativeQuery(
                                    "UPDATE campaign_execution_configurations "
                                            + "SET daily_dial_limit = ? "
                                            + "WHERE campaign_id = ?")
                                .setParameter(1, (short) invalid)
                                .setParameter(2, campaignId)
                                .executeUpdate()),
                    "ck_cec_daily_dial_limit",
                    "campaign_execution_configurations.daily_dial_limit=" + invalid);
        }
        assertThat(snapshotLimit(executionId)).isEqualTo(2);

        // Null and the valid range remain writable.
        tx().executeWithoutResult(t -> entityManager.createNativeQuery(
                "UPDATE campaigns SET daily_dial_limit = NULL WHERE id = ?")
            .setParameter(1, campaignId).executeUpdate());
        assertThat(campaignLimit(campaignId)).isNull();
        tx().executeWithoutResult(t -> entityManager.createNativeQuery(
                "UPDATE campaigns SET daily_dial_limit = 3 WHERE id = ?")
            .setParameter(1, campaignId).executeUpdate());
        assertThat(campaignLimit(campaignId)).isEqualTo(3);
    }

    /**
     * Asserts the database itself refused the write, and — more precisely
     * than an exception class — that the NAMED V48 check constraint is what
     * rejected it. A native {@code UPDATE} surfaces Hibernate's
     * {@code ConstraintViolationException} (not Spring's
     * {@code DataIntegrityViolationException}, which is only translated on
     * the JPA/connection paths), so both shapes are accepted while the
     * constraint name is asserted strictly.
     */
    private void assertRejectedByCheck(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable write,
            String constraintName,
            String description) {
        assertThatThrownBy(write)
                .as("%s must be rejected by %s", description, constraintName)
                .isInstanceOfAny(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        org.hibernate.exception.ConstraintViolationException.class)
                .hasMessageContaining(constraintName);
    }

    @Test
    @DisplayName("PG-6C2-5: migration chain reaches V48 on a clean database")
    void migrationChainReachesV48() {
        Object version = entityManager.createNativeQuery(
            "SELECT version FROM flyway_schema_history WHERE version = '48'")
            .getSingleResult();
        assertThat(version).isEqualTo("48");

        // The snapshot table carries the frozen column.
        Number columns = (Number) entityManager.createNativeQuery(
            "SELECT COUNT(*) FROM information_schema.columns "
            + "WHERE table_name = 'campaign_execution_configurations' "
            + "AND column_name = 'daily_dial_limit'")
            .getSingleResult();
        assertThat(columns.intValue()).isEqualTo(1);
    }
}
