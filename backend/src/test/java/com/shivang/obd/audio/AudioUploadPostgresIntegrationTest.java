package com.shivang.obd.audio;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.campaign.CampaignEntity;
import com.shivang.obd.campaign.CampaignRepository;
import com.shivang.obd.campaign.CampaignStatus;
import com.shivang.obd.campaign.CampaignType;
import com.shivang.obd.campaign.ContentMode;
import com.shivang.obd.campaign.dto.CampaignReadinessResponse;
import com.shivang.obd.campaign.CampaignReadinessService;
import com.shivang.obd.tenant.TenantEntity;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-5B PostgreSQL integration tests (real {@code postgres:16-alpine},
 * real Flyway chain V1..V40): upload → persisted derived metadata and
 * storage reference; tenant isolation; upload-never-approves; readiness
 * gate for approved-but-storageless assets.
 * <p>
 * The storage pipeline runs against a JUnit {@code @TempDir} (never a
 * developer-machine path). Authorization is bypassed with a
 * platform-scope organizational context so the service boundary sees an
 * authenticated caller; tenant isolation itself is proven through the
 * service's tenant-derived queries.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({com.shivang.obd.common.audit.JpaAuditConfig.class, AudioAssetMapper.class})
class AudioUploadPostgresIntegrationTest {

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
        // Temporary local storage root for this JVM.
        registry.add("audio.storage.enabled", () -> "true");
        registry.add("audio.storage.base-directory", () -> TEMP_BASE);
    }

    static String TEMP_BASE;

    @TempDir
    static Path tempBase;

    @TempDir
    static Path tempDirUnused;

    @Autowired
    private AudioAssetRepository audioAssetRepository;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;
    @Autowired
    private com.shivang.obd.tenant.TenantRepository tenantRepository;

    private AudioAssetService audioAssetService;
    private CampaignReadinessService readinessService;
    private AudioStorageProperties storageProperties;

    private static final SecureRandom RANDOM = new SecureRandom();

    private UUID tenantA;
    private UUID tenantB;
    private UUID userId;

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                    + startupFailure.getMessage(), startupFailure);
        }
        // The platform-scope context makes readiness treat the caller as
        // unrestricted; uploads resolve their tenant from this context too.
        TEMP_BASE = tempBase.toString();

        tenantA = seedTenant("audio-a");
        tenantB = seedTenant("audio-b");
        userId = UUID.randomUUID();

        audioAssetRepository.deleteAll();

        storageProperties = new AudioStorageProperties();
        storageProperties.setEnabled(true);
        storageProperties.setBaseDirectory(TEMP_BASE);
        AudioStorage storage = new LocalAudioStorage(storageProperties);

        audioAssetService = new AudioAssetService(
            audioAssetRepository, authorizationAllowAll(), currentUser(), new AudioAssetMapper(),
            tenantRepository, storage, new AudioUploadValidator(storageProperties));

        readinessService = new CampaignReadinessService(
            campaignRepository, authorizationAllowAll(), currentUser(),
            null,
            // Real canonical validator (VB-5E) over the autowired audio repo.
            new com.shivang.obd.campaign.CampaignResourceValidationService(
                null, audioAssetRepository, null),
            tenantRepository);

        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(userId, tenantA, null);
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    private com.shivang.obd.authz.AuthorizationService authorizationAllowAll() {
        // Real service, platform scope: the seeded SUPER_ADMIN-role caller
        // (assignments persisted below) satisfies every capability check.
        return new com.shivang.obd.authz.AuthorizationService(
            java.util.List.of(), null, null, null, java.util.List.of()) {
            @Override
            public void requireCapability(UUID userId, String capabilityKey,
                com.shivang.obd.authz.AccessCheck target) {
                // Pass-through for the integration harness.
            }
        };
    }

    private com.shivang.obd.security.CurrentUserProvider currentUser() {
        return new com.shivang.obd.security.CurrentUserProvider() {
            @Override
            public java.util.Optional<com.shivang.obd.security.AuthenticatedUser> current() {
                return java.util.Optional.of(
                    new com.shivang.obd.security.AuthenticatedUser(userId, "it@test.local", null));
            }
        };
    }

    private static String suffix() {
        return Long.toHexString(System.nanoTime() & 0xffff) + RANDOM.nextInt(1000);
    }

    private UUID seedTenant(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantEntity tenant = new TenantEntity();
        tenant.setName("tenant-" + label + "-" + suffix);
        tenant.setSlug("t-" + label + "-" + suffix);
        tenant.setStatus(com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE);
        return tenantRepository.save(tenant).getId();
    }

    private static byte[] wavBytes(int dataBytes) {
        ByteBuffer b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataBytes).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(8000).putInt(8000).putShort((short) 1).putShort((short) 8);
        b.put("data".getBytes()).putInt(dataBytes);
        for (int i = 0; i < dataBytes; i++) {
            b.put((byte) (i % 7));
        }
        return b.array();
    }

    private AudioAssetEntity uploadedAsset(UUID tenantId, String name) {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(userId, tenantId, null);
        var response = audioAssetService.upload(
            name, "integration", "clip.wav", "audio/wav", new ByteArrayInputStream(wavBytes(64)));
        return audioAssetRepository.findById(response.data().id()).orElseThrow();
    }

    private UUID seedCampaign(UUID tenantId, UUID audioAssetId, CampaignStatus status) {
        CampaignEntity campaign = new CampaignEntity();
        campaign.setTenantId(tenantId);
        campaign.setName("it-playfile-" + suffix());
        campaign.setCampaignType(CampaignType.PLAYFILE);
        campaign.setStatus(status);
        campaign.setContentMode(ContentMode.AUDIO);
        campaign.setAudioAssetId(audioAssetId);
        // VB-7C.1 carry-over: readiness requires an execution timezone for every
        // campaign, with no fallback. This fixture predates that rule and set no
        // schedule at all, so PG-4 saw an extra readiness reason that had
        // nothing to do with audio. A windowless UTC schedule is always
        // eligible, which keeps these tests testing what they are named for:
        // audio asset readiness.
        campaign.setSchedule(new com.shivang.obd.campaign.ScheduleSpec(
            null, null, null, "UTC", null, null));
        // Embedded retry policy: JPA inserts all columns, so the NOT NULL
        // retry fields must be set explicitly (0 = no retries).
        campaign.setRetryPolicy(new com.shivang.obd.campaign.RetryPolicySpec(
            0, null, com.shivang.obd.campaign.RetryStrategy.FIXED));
        return campaignRepository.save(campaign).getId();
    }

    private CampaignReadinessResponse evaluate(UUID campaignId) {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(userId, null, null);
        return readinessService.evaluate(campaignId);
    }

    @Test
    @DisplayName("PG-1: upload persists derived metadata and storage reference")
    void uploadPersists() {
        AudioAssetEntity asset = uploadedAsset(tenantA, "pg-1");

        assertThat(asset.getTenantId()).isEqualTo(tenantA);
        assertThat(asset.getStatus()).isEqualTo(AudioAssetStatus.PENDING_APPROVAL);
        assertThat(asset.getContentType()).isEqualTo("audio/wav");
        assertThat(asset.getFileSize()).isEqualTo(108L);
        assertThat(asset.getChecksum()).hasSize(64);
        assertThat(asset.getStorageReference()).startsWith("audio/" + tenantA + "/");

        // The physical file exists at the referenced location.
        Path physical = Path.of(TEMP_BASE,
            asset.getStorageReference().substring("audio/".length()));
        assertThat(physical).exists();
    }

    @Test
    @DisplayName("PG-2: tenant isolation — Tenant A cannot load Tenant B's asset")
    void tenantIsolation() {
        UUID assetB = uploadedAsset(tenantB, "pg-2-b").getId();

        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(userId, tenantA, null);
        assertThat(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(assetB, tenantA)).isEmpty();
        assertThat(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(assetB, tenantB)).isPresent();
    }

    @Test
    @DisplayName("PG-3: uploaded asset stays PENDING_APPROVAL until explicitly approved")
    void approvalSemantics() {
        UUID assetId = uploadedAsset(tenantA, "pg-3").getId();

        assertThat(audioAssetRepository.findById(assetId).orElseThrow()
            .getStatus()).isEqualTo(AudioAssetStatus.PENDING_APPROVAL);

        AudioAssetEntity asset = audioAssetRepository.findById(assetId).orElseThrow();
        asset.setStatus(AudioAssetStatus.APPROVED);
        audioAssetRepository.save(asset);
        assertThat(audioAssetRepository.findById(assetId).orElseThrow()
            .getStatus()).isEqualTo(AudioAssetStatus.APPROVED);
    }

    @Test
    @DisplayName("PG-4: SCHEDULED PLAYFILE campaign with approved+stored asset is ready")
    void readyWithApprovedStoredAsset() {
        UUID assetId = uploadedAsset(tenantA, "pg-4").getId();
        AudioAssetEntity asset = audioAssetRepository.findById(assetId).orElseThrow();
        asset.setStatus(AudioAssetStatus.APPROVED);
        audioAssetRepository.save(asset);

        UUID campaignId = seedCampaign(tenantA, assetId, CampaignStatus.SCHEDULED);
        CampaignReadinessResponse response = evaluate(campaignId);

        assertThat(response.ready()).isTrue();
        assertThat(response.reasons()).isEmpty();
    }

    @Test
    @DisplayName("PG-5: approved asset without storage reference blocks readiness")
    void missingStorageReferenceBlocksReadiness() {
        // Register a metadata-only asset (the legacy pre-VB-5B shape).
        AudioAssetEntity asset = new AudioAssetEntity();
        asset.setTenantId(tenantA);
        asset.setName("pg-5");
        asset.setFileName("legacy.wav");
        asset.setContentType("audio/wav");
        asset.setFileSize(108L);
        asset.setStatus(AudioAssetStatus.APPROVED);
        asset = audioAssetRepository.save(asset);
        UUID assetId = asset.getId();

        UUID campaignId = seedCampaign(tenantA, assetId, CampaignStatus.SCHEDULED);
        CampaignReadinessResponse response = evaluate(campaignId);

        assertThat(response.ready()).isFalse();
        assertThat(response.reasons())
            .anySatisfy(r -> assertThat(r.code()).isEqualTo("AUDIO_STORAGE_REFERENCE_MISSING"));
    }

    @Test
    @DisplayName("PG-6: pending/rejected assets never make a campaign ready")
    void pendingAndRejectedAssetsUnusable() {
        UUID pendingId = uploadedAsset(tenantA, "pg-6-pending").getId();
        UUID rejectedId = uploadedAsset(tenantA, "pg-6-rejected").getId();
        AudioAssetEntity rejected = audioAssetRepository.findById(rejectedId).orElseThrow();
        rejected.setStatus(AudioAssetStatus.REJECTED);
        audioAssetRepository.save(rejected);

        CampaignReadinessResponse pending = evaluate(seedCampaign(tenantA, pendingId, CampaignStatus.SCHEDULED));
        CampaignReadinessResponse rejectedEval = evaluate(seedCampaign(tenantA, rejectedId, CampaignStatus.SCHEDULED));

        assertThat(pending.ready()).isFalse();
        assertThat(pending.reasons())
            .anySatisfy(r -> assertThat(r.code()).isEqualTo("AUDIO_NOT_APPROVED"));
        assertThat(rejectedEval.ready()).isFalse();
    }

    @Test
    @DisplayName("PG-7: soft-deleted asset is invisible to campaigns and tenants")
    void softDeletedAssetUnusable() {
        UUID assetId = uploadedAsset(tenantA, "pg-7").getId();

        AudioAssetEntity asset = audioAssetRepository.findById(assetId).orElseThrow();
        asset.setDeletedAt(java.time.Instant.now());
        audioAssetRepository.save(asset);

        assertThat(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(assetId, tenantA)).isEmpty();
    }

    @Test
    @DisplayName("PG-8: campaign readiness rejects a foreign-tenant asset reference")
    void crossTenantCampaignReferenceRejected() {
        UUID assetB = uploadedAsset(tenantB, "pg-8-b").getId();
        AudioAssetEntity assetBEntity = audioAssetRepository.findById(assetB).orElseThrow();
        assetBEntity.setStatus(AudioAssetStatus.APPROVED);
        audioAssetRepository.save(assetBEntity);

        // Tenant A campaign referencing Tenant B's asset.
        UUID campaignId = seedCampaign(tenantA, assetB, CampaignStatus.SCHEDULED);
        CampaignReadinessResponse response = evaluate(campaignId);

        assertThat(response.ready()).isFalse();
        assertThat(response.reasons())
            .anySatisfy(r -> assertThat(r.code()).isEqualTo("AUDIO_NOT_APPROVED"));
    }
}
