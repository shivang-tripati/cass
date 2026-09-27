package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.dto.ExecuteCampaignRequest;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.did.AllocationSource;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.reseller.ResellerEntity;
import com.shivang.obd.reseller.ResellerRepository;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-6A correction — execution-owned immutable configuration snapshots
 * against real PostgreSQL (Flyway V1..V44):
 * <ul>
 *   <li>CFG-A — snapshot creation and content (validated typeConfig,
 *       execution-affecting fields only; integrationConfig excluded)</li>
 *   <li>CFG-B — each execution owns its own snapshot; a campaign edit
 *       affects only executions created after the edit (no version
 *       numbers — the snapshot is execution-owned, not campaign history)</li>
 *   <li>CFG-C — the running execution stays on its snapshot after the
 *       campaign is edited</li>
 *   <li>CFG-D — tenant isolation: a foreign snapshot is unresolvable and
 *       surfaces as the deterministic integrity error</li>
 *   <li>CFG-E — no legacy fallback: executions without a resolvable
 *       snapshot fail deterministically; the database itself forbids
 *       executions without a snapshot (NOT NULL FK)</li>
 * </ul>
 *
 * <p>Harness conventions (VB-4/5/6 series): static-container startup,
 * {@code NOT_SUPPORTED} propagation, services constructed directly and
 * invoked inside {@link TransactionTemplate}.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampaignConfigurationSnapshotPostgresIntegrationTest {

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
    private DidRepository didRepository;
    @Autowired
    private com.shivang.obd.contact.ContactGroupRepository contactGroupRepository;
    @Autowired
    private com.shivang.obd.audio.AudioAssetRepository audioAssetRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private ResellerRepository resellerRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final UUID CALLER_ID =
        UUID.fromString("ff000000-0000-4000-8000-0000000000f1");

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger E164_SEQ = new AtomicInteger(7000);

    private CampaignExecutionService executionService;
    private CampaignConfigurationService configurationService;
    private com.shivang.obd.authz.AuthorizationService allowAll;
    private CurrentUserProvider currentUser;
    private CampaignRuntimeConfigResolver runtimeConfigResolver;

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                    + startupFailure.getMessage(), startupFailure);
        }
        allowAll = new com.shivang.obd.authz.AuthorizationService(
            List.of(), null, null, null, List.of()) {
            @Override
            public void requireCapability(UUID userId, String capabilityKey,
                com.shivang.obd.authz.AccessCheck target) {
                // harness pass-through; scope semantics exercised below
            }
        };
        currentUser = new CurrentUserProvider() {
            @Override
            public java.util.Optional<AuthenticatedUser> current() {
                return java.util.Optional.of(
                    new AuthenticatedUser(CALLER_ID, "it@test.local", null));
            }
        };
        configurationService = new CampaignConfigurationService(
            snapshotRepository,
            new com.shivang.obd.campaign.config.CampaignTypeConfigValidator());
        runtimeConfigResolver = new CampaignRuntimeConfigResolver(configurationService);
        CampaignResourceValidationService validator = new CampaignResourceValidationService(
            didRepository, audioAssetRepository, null);
        CampaignReadinessService readinessService = new CampaignReadinessService(
            campaignRepository, allowAll, currentUser,
            contactGroupRepository, validator, tenantRepository);
        executionService = new CampaignExecutionService(
            campaignRepository, executionRepository, allowAll, currentUser,
            readinessService, tenantRepository, configurationService);
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createQuery("DELETE FROM CallAttempt").executeUpdate();
            entityManager.createQuery("DELETE FROM CampaignExecution").executeUpdate();
            entityManager.createQuery("DELETE FROM CampaignExecutionConfiguration").executeUpdate();
            entityManager.createQuery("DELETE FROM CampaignEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM AudioAssetEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM com.shivang.obd.contact.ContactGroupMemberEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactGroupEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM DidEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM TenantEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ResellerEntity").executeUpdate();
        });
    }

    // === CFG-A: snapshot creation and content ===

    @Test
    @DisplayName("CFG-A1: execution request creates an immutable snapshot with validated config")
    void executionRequestMaterializesSnapshot() {
        UUID tenantId = seedTenant("a1", null).getId();
        UUID didId = seedDid(tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID groupId = seedContactGroup(tenantId);
        UUID campaignId = seedCampaign(tenantId, didId, groupId);

        tenantScope(tenantId);
        var response = transactionTemplate.execute(tx ->
            executionService.execute(campaignId, new ExecuteCampaignRequest(null)));
        assertThat(response).isNotNull();

        UUID snapshotId = response.data().configurationSnapshotId();
        assertThat(snapshotId).isNotNull();

        CampaignExecutionConfiguration snapshot = transactionTemplate.execute(tx ->
            snapshotRepository.findById(snapshotId)).orElseThrow();
        assertThat(snapshot.getCampaignId()).isEqualTo(campaignId);
        assertThat(snapshot.getTenantId()).isEqualTo(tenantId);
        assertThat(snapshot.getConfiguration().getCampaignType()).isEqualTo(CampaignType.PLAYFILE);
        assertThat(snapshot.getConfiguration().getDidId()).isEqualTo(didId);
        assertThat(snapshot.getConfiguration().getContactGroupId()).isEqualTo(groupId);
        assertThat(snapshot.getConfiguration().getRetryMaxAttempts()).isZero();
        // PLAYFILE's canonical typeConfig is the empty object (no type-specific payload).
        assertThat(snapshot.getConfiguration().getTypeConfig().isEmpty()).isTrue();

        // Snapshot and execution exist together, one-to-one (§14).
        assertThat(countSnapshots()).isEqualTo(countExecutions(campaignId));
    }

    @Test
    @DisplayName("CFG-A2: DTMF snapshot carries the strict-validated typeConfig")
    void dtmfSnapshotCarriesValidatedTypeConfig() {
        UUID tenantId = seedTenant("a2", null).getId();
        CampaignEntity c = campaignRow(tenantId, CampaignType.DTMF, null, null);
        c.setTypeConfig(toJson("{\"dtmf\": {\"expected\": \"1\", \"timeoutSecs\": 10}}"));

        var parsed = com.shivang.obd.campaign.config.CampaignTypeConfig.fromTypeConfig(
            CampaignType.DTMF, c.getTypeConfig());
        CampaignConfigurationSnapshot snapshot =
            CampaignConfigurationService.toSnapshot(c, parsed);

        assertThat(snapshot.getTypeConfig().get("dtmf").get("expected").asText()).isEqualTo("1");
        assertThat(snapshot.getCampaignType()).isEqualTo(CampaignType.DTMF);
    }

    private tools.jackson.databind.JsonNode toJson(String json) {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
    }

    // === CFG-B: execution-owned snapshots across campaign edits ===

    @Test
    @DisplayName("CFG-B: campaign edit affects only executions created after the edit")
    void campaignEditAffectsOnlyFutureExecutions() {
        UUID tenantId = seedTenant("b1", null).getId();
        UUID didV1 = seedDid(tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID campaignId = seedCampaign(tenantId, didV1, seedContactGroup(tenantId));

        tenantScope(tenantId);
        var e1 = transactionTemplate.execute(tx ->
            executionService.execute(campaignId, new ExecuteCampaignRequest(null)));
        assertThat(e1.data().configurationSnapshotId()).isNotNull();

        // Edit the campaign (new DID reference).
        UUID didV2 = seedDid(tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            c.setDidId(didV2);
            campaignRepository.saveAndFlush(c);
        });

        var e2 = transactionTemplate.execute(tx ->
            executionService.execute(campaignId, new ExecuteCampaignRequest(null)));

        // Each execution owns its own snapshot — no sharing, no reuse.
        assertThat(e2.data().configurationSnapshotId())
            .isNotNull()
            .isNotEqualTo(e1.data().configurationSnapshotId());

        CampaignExecutionConfiguration s1 = transactionTemplate.execute(tx ->
            snapshotRepository.findById(e1.data().configurationSnapshotId())).orElseThrow();
        CampaignExecutionConfiguration s2 = transactionTemplate.execute(tx ->
            snapshotRepository.findById(e2.data().configurationSnapshotId())).orElseThrow();
        assertThat(s1.getConfiguration().getDidId()).isEqualTo(didV1);
        assertThat(s2.getConfiguration().getDidId()).isEqualTo(didV2);
    }

    // === CFG-C: running execution stays on its snapshot ===

    @Test
    @DisplayName("CFG-C: existing execution keeps its snapshot after the campaign is edited")
    void runningExecutionKeepsSnapshot() {
        UUID tenantId = seedTenant("c1", null).getId();
        UUID didV1 = seedDid(tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID campaignId = seedCampaign(tenantId, didV1, seedContactGroup(tenantId));

        tenantScope(tenantId);
        var e1 = transactionTemplate.execute(tx ->
            executionService.execute(campaignId, new ExecuteCampaignRequest(null)));

        UUID didV2 = seedDid(tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            c.setDidId(didV2);
            campaignRepository.saveAndFlush(c);
        });

        // Existing execution still references its original snapshot row.
        CampaignExecution live1 = transactionTemplate.execute(tx ->
            executionRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                e1.data().id(), tenantId)).orElseThrow();
        assertThat(live1.getConfigurationSnapshotId())
            .isEqualTo(e1.data().configurationSnapshotId());

        // And the resolved runtime config still shows the original DID.
        var resolved = runtimeConfigResolver.resolve(live1);
        assertThat(resolved.didId()).isEqualTo(didV1);
    }

    // === CFG-D: tenant isolation ===

    @Test
    @DisplayName("CFG-D: a foreign snapshot is unresolvable and fails as an integrity error")
    void foreignSnapshotIsUnresolvable() {
        UUID tenantA = seedTenant("d1a", null).getId();
        UUID tenantB = seedTenant("d1b", null).getId();
        UUID campaignB = seedCampaign(tenantB, null, seedContactGroup(tenantB));

        // Snapshot for tenant B campaign.
        CampaignExecutionConfiguration snapshotB = transactionTemplate.execute(tx ->
            configurationService.createExecutionSnapshot(
                campaignRepository.findByIdAndDeletedAtIsNull(campaignB).orElseThrow()));

        // An execution row in tenant A pointing (illegitimately) at tenant B's
        // snapshot must not resolve it: the tenant-scoped lookup fails closed
        // with the deterministic integrity error — never the live campaign.
        CampaignExecution foreignRef = transactionTemplate.execute(tx -> {
            CampaignExecution e = new CampaignExecution();
            e.setCampaignId(campaignB);
            e.setTenantId(tenantA);
            e.setConfigurationSnapshotId(snapshotB.getId());
            e.setRequestedAt(Instant.now());
            e.setRequestedBy(CALLER_ID.toString());
            return executionRepository.saveAndFlush(e);
        });

        assertThatThrownBy(() -> runtimeConfigResolver.resolve(foreignRef))
            .isInstanceOf(ExecutionConfigurationMissingException.class);
    }

    // === CFG-E: no legacy fallback, DB-enforced ownership ===

    @Test
    @DisplayName("CFG-E1: an execution without a resolvable snapshot never resolves live configuration")
    void missingSnapshotFailsDeterministically() {
        UUID tenantId = seedTenant("e1", null).getId();
        UUID didId = seedDid(tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID campaignId = seedCampaign(tenantId, didId, seedContactGroup(tenantId));

        // Transient execution whose snapshot id resolves to nothing (e.g. a
        // corrupted or fabricated reference): the resolver must fail with the
        // integrity error instead of silently falling back to the live
        // campaign, which still exists with valid configuration.
        CampaignExecution corrupted = new CampaignExecution();
        corrupted.setCampaignId(campaignId);
        corrupted.setTenantId(tenantId);
        corrupted.setConfigurationSnapshotId(UUID.randomUUID());
        corrupted.setRequestedAt(Instant.now());
        corrupted.setRequestedBy(CALLER_ID.toString());

        assertThatThrownBy(() -> runtimeConfigResolver.resolve(corrupted))
            .isInstanceOf(ExecutionConfigurationMissingException.class);
    }

    @Test
    @DisplayName("CFG-E2: the database forbids executions without a snapshot (NOT NULL FK)")
    void executionWithoutSnapshotIsRefusedByDatabase() {
        UUID tenantId = seedTenant("e2", null).getId();
        UUID campaignId = seedCampaign(tenantId, null, seedContactGroup(tenantId));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            CampaignExecution e = new CampaignExecution();
            e.setCampaignId(campaignId);
            e.setTenantId(tenantId);
            e.setRequestedAt(Instant.now());
            e.setRequestedBy(CALLER_ID.toString());
            executionRepository.saveAndFlush(e);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    // === counters ===

    private long countSnapshots() {
        return transactionTemplate.execute(tx ->
            (long) entityManager.createQuery(
                    "SELECT COUNT(s) FROM CampaignExecutionConfiguration s", Long.class)
                .getSingleResult());
    }

    private long countExecutions(UUID campaignId) {
        return transactionTemplate.execute(tx ->
            (long) entityManager.createQuery(
                    "SELECT COUNT(e) FROM CampaignExecution e WHERE e.campaignId = :cid", Long.class)
                .setParameter("cid", campaignId)
                .getSingleResult());
    }

    // === seeding ===

    private void tenantScope(UUID tenantId) {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(CALLER_ID, tenantId, null);
    }

    private ResellerEntity seedReseller(String label) {
        return transactionTemplate.execute(tx -> {
            ResellerEntity r = new ResellerEntity();
            r.setName("reseller-" + label + "-" + SEQ.incrementAndGet());
            r.setSlug("r-" + label + "-" + SEQ.incrementAndGet());
            r.setStatus(LifecycleStatus.ACTIVE);
            return resellerRepository.saveAndFlush(r);
        });
    }

    private TenantEntity seedTenant(String label, UUID resellerId) {
        return transactionTemplate.execute(tx -> {
            TenantEntity t = new TenantEntity();
            t.setName("tenant-" + label + "-" + SEQ.incrementAndGet());
            t.setSlug("t-" + label + "-" + SEQ.incrementAndGet());
            t.setStatus(LifecycleStatus.ACTIVE);
            if (resellerId != null) {
                t.setResellerId(resellerId);
            }
            return tenantRepository.saveAndFlush(t);
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

    private UUID seedContactGroup(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            com.shivang.obd.contact.ContactGroupEntity g =
                new com.shivang.obd.contact.ContactGroupEntity();
            g.setTenantId(tenantId);
            g.setName("cg-" + SEQ.incrementAndGet());
            return contactGroupRepository.saveAndFlush(g).getId();
        });
    }

    private UUID seedCampaign(UUID tenantId, UUID didId, UUID contactGroupId) {
        return transactionTemplate.execute(tx -> {
            UUID assetId = seedApprovedAudioAsset(tenantId);
            CampaignEntity c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-vb6a-" + SEQ.incrementAndGet());
            c.setCampaignType(CampaignType.PLAYFILE);
            c.setStatus(CampaignStatus.SCHEDULED);
            c.setContentMode(ContentMode.AUDIO);
            c.setAudioAssetId(assetId);
            c.setDidId(didId);
            c.setContactGroupId(contactGroupId);
            c.setSchedule(alwaysEligibleSchedule());
            c.setRetryPolicy(new RetryPolicySpec(0, null, RetryStrategy.FIXED));
            return campaignRepository.saveAndFlush(c).getId();
        });
    }

    /** Schedule with a timezone and a wide-open window — always eligible. */
    private com.shivang.obd.campaign.ScheduleSpec alwaysEligibleSchedule() {
        return new com.shivang.obd.campaign.ScheduleSpec(
            null, null, null, null, "UTC", null, null);
    }

    private UUID seedApprovedAudioAsset(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            com.shivang.obd.audio.AudioAssetEntity a =
                new com.shivang.obd.audio.AudioAssetEntity();
            a.setTenantId(tenantId);
            a.setName("asset-" + SEQ.incrementAndGet());
            a.setFileName("asset-" + SEQ.incrementAndGet() + ".wav");
            a.setContentType("audio/wav");
            a.setFileSize(1024L);
            a.setStorageReference("s3://vb6a/" + SEQ.incrementAndGet() + ".wav");
            a.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
            return audioAssetRepository.saveAndFlush(a).getId();
        });
    }

    private CampaignEntity campaignRow(UUID tenantId, CampaignType type, UUID didId, UUID groupId) {
        CampaignEntity c = new CampaignEntity();
        c.setTenantId(tenantId);
        c.setName("c-vb6a-" + SEQ.incrementAndGet());
        c.setCampaignType(type);
        c.setStatus(CampaignStatus.SCHEDULED);
        c.setDidId(didId);
        c.setContactGroupId(groupId);
        c.setRetryPolicy(new RetryPolicySpec(0, null, RetryStrategy.FIXED));
        return c;
    }

    private static String uniqueE164() {
        return "+9199" + String.format("%08d", E164_SEQ.incrementAndGet());
    }

}
