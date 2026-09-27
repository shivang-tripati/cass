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
import org.springframework.dao.DataIntegrityViolationException;
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

    /** Real service-stack campaign creation (create → mapper → validation → save). */
    private UUID createCampaign(UUID tenantId, Integer dailyDialLimit) {
        com.shivang.obd.authz.context.OrganizationContextHolder
            .setAuthenticated(CALLER_ID, tenantId, null);
        CreateCampaignRequest request = new CreateCampaignRequest(
            "c-dll-" + SEQ.incrementAndGet(), null, CampaignType.PLAYFILE, null,
            null, seedDid(tenantId),
            ContentMode.AUDIO, seedAudio(tenantId), null,
            new ScheduleConfig(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                LocalTime.of(9, 0), LocalTime.of(18, 0), "Asia/Kolkata", Set.of(), null),
            new RetryPolicyConfig(0, null, null),
            null, null, false,
            dailyDialLimit);
        return campaignService.create(request, null).data().id();
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
                org.mockito.Mockito.mock(VoiceBlastDailyUsageEntryRepository.class))
                .effectiveLimit(runtimeConfigResolver.resolve(execution).dailyDialLimit());
        });
    }

    private Integer campaignLimit(UUID campaignId) {
        return tx().execute(t ->
            entityManager.find(CampaignEntity.class, campaignId).getDailyDialLimit());
    }

    private void updateCampaignLimit(UUID tenantId, UUID campaignId, Integer newLimit) {
        com.shivang.obd.authz.context.OrganizationContextHolder
            .setAuthenticated(CALLER_ID, tenantId, null);
        tx().executeWithoutResult(t -> {
            CampaignEntity campaign = campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            // Mirror the real PUT: mapper applies the whole config block.
            campaign.setName(campaign.getName());
            campaign.setDailyDialLimit(newLimit);
            campaignRepository.save(campaign);
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

        // Campaign changes 3 → 1 after E1 was created.
        updateCampaignLimit(tenantId, campaignId, 1);
        assertThat(campaignLimit(campaignId)).isEqualTo(1);

        // E1's frozen snapshot still carries 3, resolved as effective 3.
        assertThat(snapshotLimit(e1)).isEqualTo(3);
        assertThat(effectiveLimitOf(e1)).isEqualTo(3);

        // A NEW execution freezes the new value.
        UUID e2 = createExecution(tenantId, campaignId);
        assertThat(snapshotLimit(e2)).isEqualTo(1);
        assertThat(effectiveLimitOf(e2)).isEqualTo(1);
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
            assertThatThrownBy(() -> tx().executeWithoutResult(t ->
                    entityManager.createNativeQuery(
                        "UPDATE campaigns SET daily_dial_limit = ? WHERE id = ?")
                        .setParameter(1, (short) invalid)
                        .setParameter(2, campaignId)
                        .executeUpdate()))
                .as("daily_dial_limit=%d must violate ck_campaigns_daily_dial_limit", invalid)
                .isInstanceOf(DataIntegrityViolationException.class);
        }

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
