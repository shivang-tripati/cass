package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.audio.AudioAssetEntity;
import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.audio.AudioAssetStatus;
import com.shivang.obd.campaign.dto.CampaignReadinessResponse;
import com.shivang.obd.campaign.dto.CreateCampaignRequest;
import com.shivang.obd.campaign.dto.RetryPolicyConfig;
import com.shivang.obd.campaign.dto.ScheduleConfig;
import com.shivang.obd.campaign.dto.UpdateCampaignRequest;
import com.shivang.obd.campaign.event.CampaignEventPublisher;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.contact.ContactGroupEntity;
import com.shivang.obd.contact.ContactGroupRepository;
import com.shivang.obd.did.AllocationSource;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.tts.TtsTemplateEntity;
import com.shivang.obd.tts.TtsTemplateRepository;
import com.shivang.obd.tts.TtsTemplateScope;
import com.shivang.obd.tts.TtsTemplateStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-5E PostgreSQL integration tests (real {@code postgres:16-alpine}, real
 * Flyway chain V1..V42). Proves the canonical campaign-resource validation
 * contract against the actual database:
 * <ul>
 *   <li>D — CREATE validation through {@link CampaignService}</li>
 *   <li>E — UPDATE validation through {@link CampaignService}</li>
 *   <li>F — activation/readiness reasons through {@link CampaignReadinessService}</li>
 *   <li>G — runtime re-validation after resources change (no stale trust)</li>
 *   <li>H — idempotency / zero side effects</li>
 *   <li>I — tenant isolation and foreign-existence non-leakage</li>
 * </ul>
 *
 * <p>Harness conventions (VB-4/5 series): container started in a static
 * initializer (before {@code @DynamicPropertySource}); {@code NOT_SUPPORTED}
 * propagation; service calls wrapped in {@link TransactionTemplate} since
 * services constructed directly have inactive {@code @Transactional}.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampaignResourceValidationPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("obd")
            .withUsername("obd_user")
            .withPassword("obd_password");

    static volatile Exception startupFailure;

    static {
        // Started from a static initializer so the container is up before
        // Spring resolves @DynamicPropertySource values (harness pattern).
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
    private DidRepository didRepository;
    @Autowired
    private AudioAssetRepository audioAssetRepository;
    @Autowired
    private TtsTemplateRepository ttsTemplateRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private ContactGroupRepository contactGroupRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private CampaignService campaignService;
    private CampaignReadinessService readinessService;
    private CampaignResourceValidationService validator;
    private UUID userId;
    private UUID tenantA;
    private UUID tenantB;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                    + startupFailure.getMessage(), startupFailure);
        }
        userId = UUID.fromString("ee000000-0000-4000-8000-000000000001");
        var allowAll = new com.shivang.obd.authz.AuthorizationService(
            List.of(), null, null, null, List.of()) {
            @Override
            public void requireCapability(UUID uid, String capabilityKey,
                com.shivang.obd.authz.AccessCheck target) {
                // harness pass-through; scope semantics are covered by the
                // authorization unit suites
            }
        };
        var currentUser = new com.shivang.obd.security.CurrentUserProvider() {
            @Override
            public java.util.Optional<com.shivang.obd.security.AuthenticatedUser> current() {
                return java.util.Optional.of(
                    new com.shivang.obd.security.AuthenticatedUser(userId, "it@test.local", null));
            }
        };

        validator = new CampaignResourceValidationService(
            didRepository, audioAssetRepository, ttsTemplateRepository);
        campaignService = new CampaignService(
            campaignRepository, allowAll,
            org.mockito.Mockito.mock(CampaignEventPublisher.class),
            currentUser, new CampaignMapper(),
            tenantRepository, contactGroupRepository, validator,
            new com.shivang.obd.campaign.CampaignLifecyclePolicy());
        readinessService = new CampaignReadinessService(
            campaignRepository, allowAll, currentUser,
            contactGroupRepository, validator, tenantRepository);

        tenantA = seedTenant("vra-a");
        tenantB = seedTenant("vra-b");
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createQuery("DELETE FROM CampaignEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM DidEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM AudioAssetEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM TtsTemplateEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM com.shivang.obd.contact.ContactGroupMemberEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactGroupEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM TenantEntity").executeUpdate();
        });
    }

    // === D. CREATE ===

    @Test
    @DisplayName("PG-D1: CREATE accepts a valid tenant-owned resource combination")
    void createAcceptsValidResourceCombination() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioId = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/promo.wav");

        UUID campaignId = createCampaign(tenantA, groupId, didId, audioId, null,
            ContentMode.AUDIO);

        assertThat(campaignId).isNotNull();
        assertThat(campaignRepository.findById(campaignId)).isPresent();
    }

    @Test
    @DisplayName("PG-D2: CREATE rejects an invalid DID (foreign, unassigned, inactive, missing)")
    void createRejectsInvalidDid() {
        UUID groupId = seedContactGroup(tenantA);
        UUID audioId = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/promo.wav");
        UUID foreignDid = seedDid(tenantB, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID unassignedDid = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.AVAILABLE);
        UUID inactiveDid = seedDid(tenantA, DidStatus.INACTIVE, AllocationState.ASSIGNED);

        for (UUID badDid : List.of(foreignDid, unassignedDid, inactiveDid, UUID.randomUUID())) {
            var request = createRequest(groupId, badDid, audioId, null, ContentMode.AUDIO);
            assertThatThrownBy(() -> createCampaign(tenantA, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DID does not exist or is not available.");
        }
        assertThat(campaignRepository.count()).isZero();
    }

    @Test
    @DisplayName("PG-D3: CREATE rejects unapproved and foreign audio (single non-leaking message)")
    void createRejectsUnapprovedAndForeignAudio() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID pending = seedAudio(tenantA, AudioAssetStatus.PENDING_APPROVAL, "tenants/a/x.wav");
        UUID rejected = seedAudio(tenantA, AudioAssetStatus.REJECTED, "tenants/a/x.wav");
        UUID foreign = seedAudio(tenantB, AudioAssetStatus.APPROVED, "tenants/b/x.wav");

        for (UUID badAudio : List.of(pending, rejected, foreign, UUID.randomUUID())) {
            var request = createRequest(groupId, didId, badAudio, null, ContentMode.AUDIO);
            assertThatThrownBy(() -> createCampaign(tenantA, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Audio asset does not exist or is not approved for use.");
        }
        assertThat(campaignRepository.count()).isZero();
    }

    @Test
    @DisplayName("PG-D4: CREATE rejects foreign and unapproved TTS; accepts approved GLOBAL")
    void createTtsMatrix() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID foreignTenantTts = seedTts(TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantB);
        UUID pendingGlobalTts = seedTts(TtsTemplateScope.GLOBAL, TtsTemplateStatus.PENDING_APPROVAL, null);
        UUID approvedGlobalTts = seedTts(TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);

        for (UUID badTts : List.of(foreignTenantTts, pendingGlobalTts, UUID.randomUUID())) {
            var request = createRequest(groupId, didId, null, badTts, ContentMode.TTS);
            assertThatThrownBy(() -> createCampaign(tenantA, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TTS template does not exist or is not approved for use.");
        }

        UUID okId = createCampaign(tenantA, groupId, didId, null, approvedGlobalTts,
            ContentMode.TTS);
        assertThat(okId).isNotNull();
    }

    // === E. UPDATE ===

    @Test
    @DisplayName("PG-E1: UPDATE accepts a valid resource reference change")
    void updateAcceptsValidReferenceChange() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audio1 = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/1.wav");
        UUID audio2 = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/2.wav");
        UUID campaignId = createCampaign(tenantA, groupId, didId, audio1, null,
            ContentMode.AUDIO);

        updateCampaign(tenantA, campaignId,
            updateRequest(groupId, didId, audio2, null, ContentMode.AUDIO));

        assertThat(entityManager.find(CampaignEntity.class, campaignId).getAudioAssetId())
            .isEqualTo(audio2);
    }

    @Test
    @DisplayName("PG-E2: UPDATE rejects invalid DID and foreign audio references")
    void updateRejectsInvalidReferences() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioId = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/1.wav");
        UUID foreignDid = seedDid(tenantB, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID foreignAudio = seedAudio(tenantB, AudioAssetStatus.APPROVED, "tenants/b/x.wav");
        UUID campaignId = createCampaign(tenantA, groupId, didId, audioId, null,
            ContentMode.AUDIO);

        assertThatThrownBy(() -> updateCampaign(tenantA, campaignId,
            updateRequest(groupId, foreignDid, audioId, null, ContentMode.AUDIO)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("DID does not exist or is not available.");

        assertThatThrownBy(() -> updateCampaign(tenantA, campaignId,
            updateRequest(groupId, didId, foreignAudio, null, ContentMode.AUDIO)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Audio asset does not exist or is not approved for use.");

        // Rejected updates must not have mutated the campaign.
        CampaignEntity reloaded = entityManager.find(CampaignEntity.class, campaignId);
        assertThat(reloaded.getDidId()).isEqualTo(didId);
        assertThat(reloaded.getAudioAssetId()).isEqualTo(audioId);
    }

    @Test
    @DisplayName("PG-E3: content-mode transition is validated with the target mode's semantics")
    void updateContentModeTransitionIsValidated() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioId = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/1.wav");
        UUID foreignTts = seedTts(TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantB);
        UUID globalTts = seedTts(TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);
        UUID campaignId = createCampaign(tenantA, groupId, didId, audioId, null,
            ContentMode.AUDIO);

        // AUDIO → TTS with a foreign TENANT template is rejected.
        assertThatThrownBy(() -> updateCampaign(tenantA, campaignId,
            updateRequest(groupId, didId, null, foreignTts, ContentMode.TTS)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("TTS template does not exist or is not approved for use.");

        // AUDIO → TTS with an approved GLOBAL template is accepted.
        updateCampaign(tenantA, campaignId,
            updateRequest(groupId, didId, null, globalTts, ContentMode.TTS));

        CampaignEntity reloaded = entityManager.find(CampaignEntity.class, campaignId);
        assertThat(reloaded.getContentMode()).isEqualTo(ContentMode.TTS);
        assertThat(reloaded.getTtsTemplateId()).isEqualTo(globalTts);
        assertThat(reloaded.getAudioAssetId()).isNull();
    }

    // === F. ACTIVATION / READINESS ===

    @Test
    @DisplayName("PG-F1: campaign with valid resources has no resource readiness reasons")
    void validCampaignIsResourceReady() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioId = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/promo.wav");
        UUID campaignId = seedCampaignRow(tenantA, CampaignStatus.SCHEDULED,
            ContentMode.AUDIO, audioId, null, didId, groupId);

        List<String> codes = reasonCodes(campaignId);

        assertThat(codes).doesNotContain(
            "DID_UNAVAILABLE", "AUDIO_NOT_APPROVED", "AUDIO_STORAGE_REFERENCE_MISSING",
            "TTS_TEMPLATE_NOT_APPROVED", "TTS_TEMPLATE_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("PG-F2: revoked DID blocks readiness with DID_UNAVAILABLE")
    void revokedDidBlocksReadiness() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioId = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/promo.wav");
        UUID campaignId = seedCampaignRow(tenantA, CampaignStatus.SCHEDULED,
            ContentMode.AUDIO, audioId, null, didId, groupId);

        transactionTemplate.executeWithoutResult(tx -> {
            DidEntity did = entityManager.find(DidEntity.class, didId);
            did.setStatus(DidStatus.INACTIVE);
            didRepository.saveAndFlush(did);
        });

        assertThat(reasonCodes(campaignId)).contains("DID_UNAVAILABLE");
    }

    @Test
    @DisplayName("PG-F3: unapproved and storage-less audio map to the existing reason split")
    void audioReasonsPreserveExistingSplit() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID pending = seedAudio(tenantA, AudioAssetStatus.PENDING_APPROVAL, "tenants/a/x.wav");
        UUID approvedNoStorage = seedAudio(tenantA, AudioAssetStatus.APPROVED, null);
        UUID c1 = seedCampaignRow(tenantA, CampaignStatus.SCHEDULED,
            ContentMode.AUDIO, pending, null, didId, groupId);
        UUID c2 = seedCampaignRow(tenantA, CampaignStatus.SCHEDULED,
            ContentMode.AUDIO, approvedNoStorage, null, didId, groupId);

        assertThat(reasonCodes(c1)).contains("AUDIO_NOT_APPROVED");
        assertThat(reasonCodes(c2)).contains("AUDIO_STORAGE_REFERENCE_MISSING");
    }

    @Test
    @DisplayName("PG-F4: TTS reason split is preserved tenant-safely; approved GLOBAL is ready")
    void ttsReasonsPreserveExistingSplit() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID pendingTenantTts = seedTts(TtsTemplateScope.TENANT, TtsTemplateStatus.PENDING_APPROVAL, tenantA);
        UUID foreignTts = seedTts(TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantB);
        UUID approvedGlobalTts = seedTts(TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);
        UUID c1 = seedCampaignRow(tenantA, CampaignStatus.SCHEDULED,
            ContentMode.TTS, null, pendingTenantTts, didId, groupId);
        UUID c2 = seedCampaignRow(tenantA, CampaignStatus.SCHEDULED,
            ContentMode.TTS, null, foreignTts, didId, groupId);
        UUID c3 = seedCampaignRow(tenantA, CampaignStatus.SCHEDULED,
            ContentMode.TTS, null, approvedGlobalTts, didId, groupId);

        assertThat(reasonCodes(c1)).contains("TTS_TEMPLATE_NOT_APPROVED");
        assertThat(reasonCodes(c2)).contains("TTS_TEMPLATE_NOT_AVAILABLE");
        assertThat(reasonCodes(c3)).doesNotContain(
            "TTS_TEMPLATE_NOT_APPROVED", "TTS_TEMPLATE_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("PG-F5: revoked GLOBAL approval blocks readiness as TTS_TEMPLATE_NOT_APPROVED")
    void revokedGlobalApprovalBlocksReadiness() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID globalTts = seedTts(TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);
        UUID campaignId = seedCampaignRow(tenantA, CampaignStatus.SCHEDULED,
            ContentMode.TTS, null, globalTts, didId, groupId);

        assertThat(reasonCodes(campaignId)).doesNotContain("TTS_TEMPLATE_NOT_APPROVED");

        transactionTemplate.executeWithoutResult(tx -> {
            TtsTemplateEntity template = entityManager.find(TtsTemplateEntity.class, globalTts);
            template.setStatus(TtsTemplateStatus.REJECTED);
            ttsTemplateRepository.saveAndFlush(template);
        });

        assertThat(reasonCodes(campaignId)).contains("TTS_TEMPLATE_NOT_APPROVED");
    }

    // === G. RUNTIME (resource invalidation after create/activation) ===

    @Test
    @DisplayName("PG-G1: DID revoked after CREATE is rejected at runtime re-validation")
    void revokedDidIsDetectedAfterCreation() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioId = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/promo.wav");
        UUID campaignId = createCampaign(tenantA, groupId, didId, audioId, null,
            ContentMode.AUDIO);

        assertThat(validator.validateDid(didId, tenantA).usable()).isTrue();

        transactionTemplate.executeWithoutResult(tx -> {
            DidEntity did = entityManager.find(DidEntity.class, didId);
            did.setStatus(DidStatus.INACTIVE);
            didRepository.saveAndFlush(did);
        });

        // Runtime validation re-reads canonical state — never trusts CREATE's result.
        assertThat(validator.validateDid(didId, tenantA).usable()).isFalse();
        assertThat(reasonCodes(campaignId)).contains("DID_UNAVAILABLE");
    }

    @Test
    @DisplayName("PG-G2: audio deletion / approval revocation / storage loss detected at runtime")
    void audioInvalidationIsDetectedAfterCreation() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioId = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/promo.wav");
        createCampaign(tenantA, groupId, didId, audioId, null, ContentMode.AUDIO);

        transactionTemplate.executeWithoutResult(tx -> {
            AudioAssetEntity asset = entityManager.find(AudioAssetEntity.class, audioId);
            asset.setStatus(AudioAssetStatus.PENDING_APPROVAL);
            audioAssetRepository.saveAndFlush(asset);
        });
        assertThat(validator.validateAudio(audioId, tenantA).code())
            .isEqualTo(CampaignResourceValidationService.ValidationCode.AUDIO_NOT_APPROVED);

        transactionTemplate.executeWithoutResult(tx -> {
            AudioAssetEntity asset = entityManager.find(AudioAssetEntity.class, audioId);
            asset.setDeletedAt(Instant.now());
            audioAssetRepository.saveAndFlush(asset);
        });
        assertThat(validator.validateAudio(audioId, tenantA).code())
            .isEqualTo(CampaignResourceValidationService.ValidationCode.AUDIO_NOT_AVAILABLE);

        UUID storageLost = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/lost.wav");
        transactionTemplate.executeWithoutResult(tx -> {
            AudioAssetEntity asset = entityManager.find(AudioAssetEntity.class, storageLost);
            asset.setStorageReference(null);
            audioAssetRepository.saveAndFlush(asset);
        });
        assertThat(validator.validateAudio(storageLost, tenantA).code())
            .isEqualTo(CampaignResourceValidationService.ValidationCode.AUDIO_STORAGE_REFERENCE_MISSING);
    }

    @Test
    @DisplayName("PG-G3: TTS approval revocation and deletion detected at runtime")
    void ttsInvalidationIsDetectedAfterCreation() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID globalTts = seedTts(TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);
        createCampaign(tenantA, groupId, didId, null, globalTts, ContentMode.TTS);

        transactionTemplate.executeWithoutResult(tx -> {
            TtsTemplateEntity template = entityManager.find(TtsTemplateEntity.class, globalTts);
            template.setStatus(TtsTemplateStatus.PENDING_APPROVAL);
            ttsTemplateRepository.saveAndFlush(template);
        });
        assertThat(validator.validateTts(globalTts, tenantA).code())
            .isEqualTo(CampaignResourceValidationService.ValidationCode.TTS_NOT_APPROVED);

        transactionTemplate.executeWithoutResult(tx -> {
            TtsTemplateEntity template = entityManager.find(TtsTemplateEntity.class, globalTts);
            template.setDeletedAt(Instant.now());
            ttsTemplateRepository.saveAndFlush(template);
        });
        assertThat(validator.validateTts(globalTts, tenantA).code())
            .isEqualTo(CampaignResourceValidationService.ValidationCode.TTS_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("PG-G4: foreign audio referenced by a campaign row is unusable at runtime")
    void foreignAudioIsNotUsableAtRuntime() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID foreignAudio = seedAudio(tenantB, AudioAssetStatus.APPROVED, "tenants/b/x.wav");
        // Row seeded directly: the write path rejects this combination.
        UUID campaignId = seedCampaignRow(tenantA, CampaignStatus.SCHEDULED,
            ContentMode.AUDIO, foreignAudio, null, didId, groupId);

        CampaignEntity campaign = entityManager.find(CampaignEntity.class, campaignId);
        var result = validator.validateAudio(campaign.getAudioAssetId(), tenantA);

        assertThat(result.usable()).isFalse();
        assertThat(result.code())
            .isEqualTo(CampaignResourceValidationService.ValidationCode.AUDIO_NOT_AVAILABLE);
    }

    // === H. IDEMPOTENCY / SIDE EFFECTS ===

    @Test
    @DisplayName("PG-H1: repeated validation gives identical results and mutates nothing")
    void repeatedValidationMutatesNothing() {
        UUID groupId = seedContactGroup(tenantA);
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioId = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/promo.wav");
        UUID globalTts = seedTts(TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);
        UUID campaignId = seedCampaignRow(tenantA, CampaignStatus.SCHEDULED,
            ContentMode.AUDIO, audioId, null, didId, groupId);

        long campaignsBefore = campaignRepository.count();
        long didsBefore = didRepository.count();
        long audioBefore = audioAssetRepository.count();
        long ttsBefore = ttsTemplateRepository.count();
        var expectedCampaign = entityManager.find(CampaignEntity.class, campaignId);
        Instant didUpdatedAt = entityManager.find(DidEntity.class, didId).getUpdatedAt();
        Instant audioUpdatedAt = entityManager.find(AudioAssetEntity.class, audioId).getUpdatedAt();

        for (int i = 0; i < 3; i++) {
            assertThat(validator.validateDid(didId, tenantA).usable()).isTrue();
            assertThat(validator.validateAudio(audioId, tenantA).usable()).isTrue();
            assertThat(validator.validateTts(globalTts, tenantA).usable()).isTrue();
        }

        assertThat(campaignRepository.count()).isEqualTo(campaignsBefore);
        assertThat(didRepository.count()).isEqualTo(didsBefore);
        assertThat(audioAssetRepository.count()).isEqualTo(audioBefore);
        assertThat(ttsTemplateRepository.count()).isEqualTo(ttsBefore);
        CampaignEntity reloaded = entityManager.find(CampaignEntity.class, campaignId);
        assertThat(reloaded.getDidId()).isEqualTo(expectedCampaign.getDidId());
        assertThat(reloaded.getAudioAssetId()).isEqualTo(expectedCampaign.getAudioAssetId());
        assertThat(reloaded.getContentMode()).isEqualTo(expectedCampaign.getContentMode());
        assertThat(reloaded.getStatus()).isEqualTo(expectedCampaign.getStatus());
        assertThat(reloaded.getName()).isEqualTo(expectedCampaign.getName());
        assertThat(entityManager.find(DidEntity.class, didId).getUpdatedAt())
            .isEqualTo(didUpdatedAt);
        assertThat(entityManager.find(AudioAssetEntity.class, audioId).getUpdatedAt())
            .isEqualTo(audioUpdatedAt);
    }

    // === I. TENANT ISOLATION ===

    @Test
    @DisplayName("PG-I1: cross-tenant resources are unusable; approved GLOBAL TTS is usable")
    void tenantIsolationMatrix() {
        UUID didA = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID didB = seedDid(tenantB, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioA = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/x.wav");
        UUID audioB = seedAudio(tenantB, AudioAssetStatus.APPROVED, "tenants/b/x.wav");
        UUID ttsA = seedTts(TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantA);
        UUID ttsB = seedTts(TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantB);
        UUID globalTts = seedTts(TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);

        // A cannot use B's resources; B cannot use A's resources.
        assertThat(validator.validateDid(didB, tenantA).usable()).isFalse();
        assertThat(validator.validateAudio(audioB, tenantA).usable()).isFalse();
        assertThat(validator.validateTts(ttsB, tenantA).usable()).isFalse();
        assertThat(validator.validateDid(didA, tenantB).usable()).isFalse();
        assertThat(validator.validateAudio(audioA, tenantB).usable()).isFalse();
        assertThat(validator.validateTts(ttsA, tenantB).usable()).isFalse();

        // Own resources are usable; approved GLOBAL is usable by every tenant.
        assertThat(validator.validateDid(didA, tenantA).usable()).isTrue();
        assertThat(validator.validateAudio(audioA, tenantA).usable()).isTrue();
        assertThat(validator.validateTts(ttsA, tenantA).usable()).isTrue();
        assertThat(validator.validateTts(globalTts, tenantA).usable()).isTrue();
        assertThat(validator.validateTts(globalTts, tenantB).usable()).isTrue();
    }

    @Test
    @DisplayName("PG-I2: foreign and nonexistent resources fail with one non-leaking message")
    void foreignExistenceIsNotLeaked() {
        UUID didB = seedDid(tenantB, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioB = seedAudio(tenantB, AudioAssetStatus.APPROVED, "tenants/b/x.wav");
        UUID ttsB = seedTts(TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantB);
        UUID groupId = seedContactGroup(tenantA);
        UUID didA = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioA = seedAudio(tenantA, AudioAssetStatus.APPROVED, "tenants/a/x.wav");

        // Write path: identical message for a foreign reference and a nonexistent one.
        String foreignAudioMessage = createFailureMessage(
            createRequest(groupId, didA, audioB, null, ContentMode.AUDIO));
        String missingAudioMessage = createFailureMessage(
            createRequest(groupId, didA, UUID.randomUUID(), null, ContentMode.AUDIO));
        assertThat(foreignAudioMessage).isEqualTo(missingAudioMessage);

        String foreignDidMessage = createFailureMessage(
            createRequest(groupId, didB, audioA, null, ContentMode.AUDIO));
        String missingDidMessage = createFailureMessage(
            createRequest(groupId, UUID.randomUUID(), audioA, null, ContentMode.AUDIO));
        assertThat(foreignDidMessage).isEqualTo(missingDidMessage);

        String foreignTtsMessage = createFailureMessage(
            createRequest(groupId, didA, null, ttsB, ContentMode.TTS));
        String missingTtsMessage = createFailureMessage(
            createRequest(groupId, didA, null, UUID.randomUUID(), ContentMode.TTS));
        assertThat(foreignTtsMessage).isEqualTo(missingTtsMessage);
    }

    @Test
    @DisplayName("PG-I3: readiness of a foreign campaign is 404-cloaked")
    void readinessCloaksForeignCampaign() {
        UUID groupId = seedContactGroup(tenantB);
        UUID didB = seedDid(tenantB, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID audioB = seedAudio(tenantB, AudioAssetStatus.APPROVED, "tenants/b/x.wav");
        UUID campaignB = seedCampaignRow(tenantB, CampaignStatus.SCHEDULED,
            ContentMode.AUDIO, audioB, null, didB, groupId);

        tenantScope(tenantA);
        assertThatThrownBy(() ->
            transactionTemplate.executeWithoutResult(tx -> readinessService.evaluate(campaignB)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // === helpers ===

    private void tenantScope(UUID tenantId) {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(userId, tenantId, null);
    }

    private UUID seedTenant(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantEntity t = new TenantEntity();
        t.setName("tenant-" + label + "-" + suffix);
        t.setSlug("t-" + label + "-" + suffix);            t.setStatus(LifecycleStatus.ACTIVE);
        return transactionTemplate.execute(tx -> tenantRepository.saveAndFlush(t)).getId();
    }

    private UUID seedContactGroup(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            ContactGroupEntity g = new ContactGroupEntity();
            g.setTenantId(tenantId);
            g.setName("cg-" + SEQ.incrementAndGet());
            return contactGroupRepository.saveAndFlush(g).getId();
        });
    }

    private UUID seedDid(UUID tenantId, DidStatus status, AllocationState allocationState) {
        return transactionTemplate.execute(tx -> {
            DidEntity d = new DidEntity();
            d.setE164Number(uniqueE164());
            d.setCountryCode("+91");
            d.setNumberType(com.shivang.obd.did.NumberType.MOBILE);
            d.setProvider("TATA");
            d.setStatus(status);
            d.setAllocationState(allocationState);
            d.setAllocationSource(AllocationSource.PLATFORM);
            d.setTenantId(tenantId);
            return didRepository.saveAndFlush(d).getId();
        });
    }

    private UUID seedAudio(UUID tenantId, AudioAssetStatus status, String storageReference) {
        return transactionTemplate.execute(tx -> {
            AudioAssetEntity a = new AudioAssetEntity();
            a.setTenantId(tenantId);
            a.setName("asset-" + SEQ.incrementAndGet());
            a.setFileName("asset-" + SEQ.get() + ".wav");
            a.setContentType("audio/wav");
            a.setFileSize(1024L);
            a.setStatus(status);
            a.setStorageReference(storageReference);
            return audioAssetRepository.saveAndFlush(a).getId();
        });
    }

    private UUID seedTts(TtsTemplateScope scope, TtsTemplateStatus status, UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            TtsTemplateEntity e = new TtsTemplateEntity();
            e.setName("tts-" + SEQ.incrementAndGet());
            e.setTemplateText("Hello.");
            e.setVariables(List.of());
            e.setTenantId(tenantId);
            e.setScope(scope);
            e.setStatus(status);
            return ttsTemplateRepository.saveAndFlush(e).getId();
        });
    }

    /** Direct row seeding (bypasses service validation) for readiness/runtime tests. */
    private UUID seedCampaignRow(UUID tenantId, CampaignStatus status, ContentMode contentMode,
                                 UUID audioAssetId, UUID ttsTemplateId, UUID didId,
                                 UUID contactGroupId) {
        return transactionTemplate.execute(tx -> {
            CampaignEntity c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-vra-" + SEQ.incrementAndGet());
            c.setCampaignType(CampaignType.PLAYFILE);
            c.setStatus(status);
            c.setContentMode(contentMode);
            c.setAudioAssetId(audioAssetId);
            c.setTtsTemplateId(ttsTemplateId);
            c.setDidId(didId);
            c.setContactGroupId(contactGroupId);
            c.setRetryPolicy(new RetryPolicySpec(0, null, RetryStrategy.FIXED));
            return campaignRepository.saveAndFlush(c).getId();
        });
    }

    private UUID createCampaign(UUID tenantId, UUID groupId, UUID didId, UUID audioId,
                                UUID ttsId, ContentMode contentMode) {
        return createCampaign(tenantId,
            createRequest(groupId, didId, audioId, ttsId, contentMode));
    }

    private UUID createCampaign(UUID tenantId, CreateCampaignRequest request) {
        tenantScope(tenantId);
        return transactionTemplate.execute(tx ->
            campaignService.create(request, null).data().id());
    }

    private void updateCampaign(UUID tenantId, UUID campaignId, UpdateCampaignRequest request) {
        tenantScope(tenantId);
        transactionTemplate.executeWithoutResult(tx ->
            campaignService.update(campaignId, request));
    }

    private String createFailureMessage(CreateCampaignRequest request) {
        try {
            createCampaign(tenantA, request);
            throw new AssertionError("expected campaign creation to fail");
        } catch (BusinessException e) {
            return e.getMessage();
        }
    }

    private List<String> reasonCodes(UUID campaignId) {
        tenantScope(tenantA);
        CampaignReadinessResponse response = transactionTemplate.execute(tx ->
            readinessService.evaluate(campaignId));
        return response.reasons().stream().map(r -> r.code()).toList();
    }

    private CreateCampaignRequest createRequest(UUID groupId, UUID didId, UUID audioId,
                                                UUID ttsId, ContentMode contentMode) {
        return new CreateCampaignRequest(
            "c-create-" + SEQ.incrementAndGet(), null, CampaignType.PLAYFILE, null,
            groupId, didId,
            contentMode, audioId, ttsId,
            scheduleConfig(),
            new RetryPolicyConfig(0, null, null),
            null, null, false, null);
    }

    private UpdateCampaignRequest updateRequest(UUID groupId, UUID didId, UUID audioId,
                                                UUID ttsId, ContentMode contentMode) {
        return new UpdateCampaignRequest(
            "c-update-" + SEQ.incrementAndGet(), null, null,
            groupId, didId,
            contentMode, audioId, ttsId,
            scheduleConfig(),
            null, null, null, null);
    }

    private ScheduleConfig scheduleConfig() {
        return new ScheduleConfig(
            LocalDate.now().plusDays(1), LocalDate.now().plusDays(2),
            LocalTime.of(10, 0), LocalTime.of(18, 0),
            "Asia/Kolkata", null, null);
    }

    private static final java.util.concurrent.atomic.AtomicInteger E164_SEQ =
        new java.util.concurrent.atomic.AtomicInteger(2000);

    private static String uniqueE164() {
        return "+9198" + E164_SEQ.incrementAndGet()
            + String.format("%05d", E164_SEQ.get() % 100000);
    }
}
