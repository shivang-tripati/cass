package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.config.CampaignIntegrationConfig;
import com.shivang.obd.campaign.config.ReportPrivacy;
import com.shivang.obd.campaign.config.WebhookEvent;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * VB-7C.3 — the frozen webhook + report-privacy configuration against real
 * PostgreSQL (Flyway V1..V55).
 *
 * <p>The pure semantics (canonicalisation, null/default discipline, the
 * structural runtime boundary) are covered by
 * {@code ExecutionSnapshotIntegrationConfigTest}. What only a database can show
 * is that the payload actually survives a round trip, and that isolation
 * between executions and between tenants still holds once it is stored.
 *
 * <ul>
 *   <li>SNAP-ICPG-A — persistence and reload: the frozen payload is stored,
 *       survives a reload from PostgreSQL, and reads back as the same typed
 *       configuration with its endpoint, events and privacy level intact</li>
 *   <li>SNAP-ICPG-B — mutation isolation (TEST A): editing the campaign's
 *       endpoint or events cannot change an existing execution's snapshot</li>
 *   <li>SNAP-ICPG-C — mutation isolation (TEST B): the same for report privacy</li>
 *   <li>SNAP-ICPG-D — two executions of one campaign may legitimately hold
 *       different snapshots (TEST C), with nothing ordering or versioning them</li>
 *   <li>SNAP-ICPG-E — tenant isolation: a foreign execution cannot resolve
 *       another tenant's snapshot, so it cannot read their webhook endpoint</li>
 *   <li>SNAP-ICPG-F — an unconfigured campaign stores SQL NULL, and a row with
 *       no value in the new column still reads</li>
 * </ul>
 *
 * <p>Harness conventions follow the VB-4/5/6 series: static container startup,
 * {@code NOT_SUPPORTED} propagation, services constructed directly and invoked
 * inside {@link TransactionTemplate}.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionSnapshotIntegrationConfigPostgresIntegrationTest {

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
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final UUID CALLER_ID =
            UUID.fromString("ff000000-0000-4000-8000-0000000000f1");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CampaignConfigurationService configurationService;
    private CampaignRuntimeConfigResolver runtimeConfigResolver;

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                            + startupFailure.getMessage(), startupFailure);
        }
        configurationService = new CampaignConfigurationService(
                snapshotRepository,
                new com.shivang.obd.campaign.config.CampaignTypeConfigValidator());
        runtimeConfigResolver = new CampaignRuntimeConfigResolver(configurationService);
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
            entityManager.createQuery(
                    "DELETE FROM com.shivang.obd.contact.ContactGroupMemberEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactGroupEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM DidEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM TenantEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ResellerEntity").executeUpdate();
        });
    }

    // === SNAP-ICPG-A: persistence and reload ===

    @Test
    @DisplayName("SNAP-ICPG-A1: the frozen webhook configuration survives a PostgreSQL reload")
    void frozenWebhookSurvivesReload() {
        UUID tenantId = seedTenant("a1");
        UUID campaignId = seedCampaign(tenantId, """
                {
                  "webhook": {
                    "enabled": true,
                    "endpoint": "https://example-a.test/webhook",
                    "events": ["campaign.attempt.completed"]
                  },
                  "reportPrivacy": { "policy": "MASKED" }
                }
                """);

        UUID snapshotId = createSnapshot(campaignId, tenantId);

        // Re-read in a fresh transaction: the point is what was actually written.
        CampaignIntegrationConfig reloaded = reloadSnapshot(snapshotId)
                .asIntegrationConfig().orElseThrow();

        assertThat(reloaded.isWebhookEnabled()).isTrue();
        assertThat(reloaded.webhook().endpointOrNull())
                .isEqualTo("https://example-a.test/webhook");
        assertThat(reloaded.selectedEvents()).containsExactly(WebhookEvent.ATTEMPT_COMPLETED);
        assertThat(reloaded.reportPrivacy().policy()).isEqualTo(ReportPrivacy.MASKED);
    }

    @Test
    @DisplayName("SNAP-ICPG-A2: the runtime resolver reads the frozen values from the snapshot")
    void runtimeResolverSeesFrozenValues() {
        UUID tenantId = seedTenant("a2");
        UUID campaignId = seedCampaign(tenantId, """
                {
                  "webhook": {
                    "enabled": true,
                    "endpoint": "https://example-a.test/webhook",
                    "events": ["campaign.attempt.failed", "campaign.attempt.cancelled"]
                  },
                  "reportPrivacy": { "policy": "MASKED" }
                }
                """);

        Pair created = createExecutionWithSnapshot(campaignId, tenantId);

        CampaignRuntimeConfigResolver.CampaignRuntimeConfig runtime =
                runtimeConfigResolver.resolve(created.execution());
        CampaignIntegrationConfig frozen = runtime.asIntegrationConfig().orElseThrow();

        assertThat(frozen.isWebhookEnabled()).isTrue();
        assertThat(frozen.webhook().endpointOrNull())
                .isEqualTo("https://example-a.test/webhook");
        assertThat(frozen.selectedEvents()).containsExactlyInAnyOrder(
                WebhookEvent.ATTEMPT_FAILED, WebhookEvent.ATTEMPT_CANCELLED);
        assertThat(frozen.reportPrivacy().policy()).isEqualTo(ReportPrivacy.MASKED);

        // The resolver adds no defaulting of its own: what it hands a runtime
        // consumer is exactly what the snapshot stores.
        assertThat(frozen).isEqualTo(reloadSnapshot(created.snapshotId())
                .asIntegrationConfig().orElseThrow());
    }

    @Test
    @DisplayName("SNAP-ICPG-A3: what PostgreSQL stores is the platform's interpretation")
    void storedPayloadIsTheInterpretationNotTheRequest() {
        UUID tenantId = seedTenant("a3");
        // Odd key order and an omitted "enabled": what is stored must be the
        // interpreted configuration, not the request as it arrived.
        UUID campaignId = seedCampaign(tenantId, """
                {
                  "reportPrivacy": { "policy": "FULL" },
                  "webhook": {
                    "endpoint": "https://example-a.test/webhook",
                    "events": ["campaign.attempt.failed", "campaign.attempt.completed"]
                  }
                }
                """);

        UUID snapshotId = createSnapshot(campaignId, tenantId);
        tools.jackson.databind.JsonNode stored = reloadSnapshot(snapshotId)
                .getIntegrationConfig();

        assertThat(stored).isNotNull();
        assertThat(stored.get("webhook").get("enabled").asBoolean())
                .as("the omitted flag is stored as its validated meaning, written out explicitly")
                .isFalse();
        assertThat(stored.get("webhook").get("endpoint").asText())
                .isEqualTo("https://example-a.test/webhook");
        // Events are normalised to the deterministic, duplicate-free order the
        // codec produces - note that JSONB preserves array order, so this
        // ordering survives the round trip.
        assertThat(stored.get("webhook").get("events")).hasSize(2);
        assertThat(CampaignIntegrationConfig.fromJson(stored).toJson())
                .as("content survives the round trip exactly")
                .isEqualTo(stored);

        // Object KEY ORDER is not preserved by JSONB - PostgreSQL normalises it
        // on write. That is a storage detail, not a semantic one: the typed
        // model reads by key name, so the frozen configuration a future consumer
        // sees is identical either way. Asserted here so the behaviour is
        // documented rather than discovered later.
        assertThat(stored.toString())
                .as("key order is normalised by the column type, content is not")
                .isNotEqualTo(CampaignIntegrationConfig.fromJson(stored).toJson().toString());
    }

    // === SNAP-ICPG-B: webhook mutation isolation (TEST A) ===

    @Test
    @DisplayName("SNAP-ICPG-B: editing the endpoint and events cannot change a running execution")
    void webhookMutationCannotReachAnExistingSnapshot() {
        UUID tenantId = seedTenant("b1");
        UUID campaignId = seedCampaign(tenantId, """
                {
                  "webhook": {
                    "enabled": true,
                    "endpoint": "https://example-a.test/webhook",
                    "events": ["campaign.attempt.completed"]
                  }
                }
                """);

        Pair created = createExecutionWithSnapshot(campaignId, tenantId);
        CampaignConfigurationSnapshot before = reloadSnapshot(created.snapshotId());

        // The operator changes both the endpoint and the event selection.
        mutateCampaign(campaignId, tenantId, """
                {
                  "webhook": {
                    "enabled": true,
                    "endpoint": "https://example-b.test/webhook",
                    "events": ["campaign.attempt.failed"]
                  }
                }
                """);

        CampaignConfigurationSnapshot after = reloadSnapshot(created.snapshotId());
        CampaignIntegrationConfig frozen = after.asIntegrationConfig().orElseThrow();

        assertThat(frozen.webhook().endpointOrNull())
                .as("the endpoint is immutable for this execution")
                .isEqualTo("https://example-a.test/webhook");
        assertThat(frozen.selectedEvents())
                .as("the event selection is immutable for this execution")
                .containsExactly(WebhookEvent.ATTEMPT_COMPLETED);
        assertThat(after.getIntegrationConfig())
                .as("the stored payload is identical before and after the edit")
                .isEqualTo(before.getIntegrationConfig());
    }

    @Test
    @DisplayName("SNAP-ICPG-B2: disabling the webhook after the fact cannot change a running execution")
    void disablingWebhookAfterwardsCannotReachAnExistingSnapshot() {
        UUID tenantId = seedTenant("b2");
        UUID campaignId = seedCampaign(tenantId, """
                {
                  "webhook": {
                    "enabled": true,
                    "endpoint": "https://example-a.test/webhook",
                    "events": ["campaign.attempt.completed"]
                  }
                }
                """);

        Pair created = createExecutionWithSnapshot(campaignId, tenantId);
        mutateCampaign(campaignId, tenantId, "{\"webhook\": {\"enabled\": false}}");

        assertThat(reloadSnapshot(created.snapshotId())
                .asIntegrationConfig().orElseThrow().isWebhookEnabled())
                .as("an execution configured to deliver keeps that intent")
                .isTrue();
    }

    // === SNAP-ICPG-C: privacy mutation isolation (TEST B) ===

    @Test
    @DisplayName("SNAP-ICPG-C: switching FULL to MASKED cannot change a running execution")
    void privacyMutationCannotReachAnExistingSnapshot() {
        UUID tenantId = seedTenant("c1");
        UUID campaignId = seedCampaign(tenantId, "{\"reportPrivacy\": {\"policy\": \"FULL\"}}");

        Pair created = createExecutionWithSnapshot(campaignId, tenantId);
        CampaignConfigurationSnapshot before = reloadSnapshot(created.snapshotId());
        assertThat(before.asIntegrationConfig().orElseThrow().reportPrivacy().policy())
                .isEqualTo(ReportPrivacy.FULL);

        mutateCampaign(campaignId, tenantId, "{\"reportPrivacy\": {\"policy\": \"MASKED\"}}");

        CampaignConfigurationSnapshot after = reloadSnapshot(created.snapshotId());
        assertThat(after.asIntegrationConfig().orElseThrow().reportPrivacy().policy())
                .as("privacy is frozen too, and FULL never silently becomes MASKED")
                .isEqualTo(ReportPrivacy.FULL);
        assertThat(after.getIntegrationConfig()).isEqualTo(before.getIntegrationConfig());
    }

    // === SNAP-ICPG-D: two executions, two legitimate snapshots (TEST C) ===

    @Test
    @DisplayName("SNAP-ICPG-D: two executions of one campaign may hold different frozen configs")
    void twoExecutionsMayDifferWithoutAnyVersionField() {
        UUID tenantId = seedTenant("d1");
        UUID campaignId = seedCampaign(tenantId, """
                {
                  "webhook": {
                    "enabled": true,
                    "endpoint": "https://example-a.test/webhook",
                    "events": ["campaign.attempt.completed"]
                  },
                  "reportPrivacy": { "policy": "FULL" }
                }
                """);

        Pair first = createExecutionWithSnapshot(campaignId, tenantId);

        mutateCampaign(campaignId, tenantId, """
                {
                  "webhook": {
                    "enabled": true,
                    "endpoint": "https://example-b.test/webhook",
                    "events": ["campaign.attempt.failed"]
                  },
                  "reportPrivacy": { "policy": "MASKED" }
                }
                """);

        Pair second = createExecutionWithSnapshot(campaignId, tenantId);

        CampaignIntegrationConfig a = reloadSnapshot(first.snapshotId())
                .asIntegrationConfig().orElseThrow();
        CampaignIntegrationConfig b = reloadSnapshot(second.snapshotId())
                .asIntegrationConfig().orElseThrow();

        assertThat(a.webhook().endpointOrNull()).isEqualTo("https://example-a.test/webhook");
        assertThat(a.selectedEvents()).containsExactly(WebhookEvent.ATTEMPT_COMPLETED);
        assertThat(a.reportPrivacy().policy()).isEqualTo(ReportPrivacy.FULL);

        assertThat(b.webhook().endpointOrNull()).isEqualTo("https://example-b.test/webhook");
        assertThat(b.selectedEvents()).containsExactly(WebhookEvent.ATTEMPT_FAILED);
        assertThat(b.reportPrivacy().policy()).isEqualTo(ReportPrivacy.MASKED);

        // Intentional, and deliberately unexplained by any ordering or version:
        // the two snapshots differ because their content does.
        assertThat(first.snapshotId()).isNotEqualTo(second.snapshotId());
        assertThat(snapshotRepository.count()).isEqualTo(2);
    }

    // === SNAP-ICPG-E: tenant isolation ===

    @Test
    @DisplayName("SNAP-ICPG-E1: a foreign execution cannot resolve another tenant's frozen config")
    void foreignExecutionCannotResolveTheSnapshot() {
        UUID tenantA = seedTenant("e1a");
        UUID tenantB = seedTenant("e1b");

        UUID campaignB = seedCampaign(tenantB, """
                {
                  "webhook": {
                    "enabled": true,
                    "endpoint": "https://tenant-b-secret.test/webhook",
                    "events": ["campaign.attempt.completed"]
                  },
                  "reportPrivacy": { "policy": "MASKED" }
                }
                """);
        Pair owned = createExecutionWithSnapshot(campaignB, tenantB);

        // An execution row in tenant A pointing (illegitimately) at tenant B's
        // snapshot. The tenant-scoped lookup must fail closed rather than hand
        // back another tenant's endpoint.
        CampaignExecution foreignRef = transactionTemplate.execute(tx -> {
            CampaignExecution e = new CampaignExecution();
            e.setCampaignId(campaignB);
            e.setTenantId(tenantA);
            e.setConfigurationSnapshotId(owned.snapshotId());
            e.setRequestedAt(Instant.now());
            e.setRequestedBy(CALLER_ID.toString());
            return executionRepository.saveAndFlush(e);
        });

        assertThatThrownBy(() -> runtimeConfigResolver.resolve(foreignRef))
                .as("tenant B's webhook endpoint is never reachable from tenant A")
                .isInstanceOf(ExecutionConfigurationMissingException.class);
    }

    @Test
    @DisplayName("SNAP-ICPG-E2: a cross-tenant campaign cannot be snapshotted at all")
    void crossTenantCampaignIsNotResolvable() {
        UUID tenantA = seedTenant("e2a");
        UUID tenantB = seedTenant("e2b");
        UUID campaignB = seedCampaign(tenantB,
                "{\"reportPrivacy\": {\"policy\": \"MASKED\"}}");

        java.util.Optional<CampaignEntity> crossTenantLookup =
                transactionTemplate.execute(tx ->
                        campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                                campaignB, tenantA));

        assertThat(crossTenantLookup)
                .as("the scoped lookup is the only path to a campaign row")
                .isEmpty();
    }

    // === SNAP-ICPG-F: absence is preserved on disk ===

    @Test
    @DisplayName("SNAP-ICPG-F1: an unconfigured campaign stores SQL NULL for the integration config")
    void absentConfigurationStoresSqlNull() {
        UUID tenantId = seedTenant("f1");
        UUID campaignId = seedCampaign(tenantId, null);

        UUID snapshotId = createSnapshot(campaignId, tenantId);

        Object stored = transactionTemplate.execute(tx -> entityManager
                .createNativeQuery("SELECT integration_config"
                        + " FROM campaign_execution_configurations WHERE id = :id")
                .setParameter("id", snapshotId)
                .getSingleResult());

        assertThat(stored)
                .as("NULL on disk, so 'never configured' survives the round trip")
                .isNull();
        assertThat(reloadSnapshot(snapshotId).asIntegrationConfig()).isEmpty();
    }

    @Test
    @DisplayName("SNAP-ICPG-F2: a row with no value in the new column still reads")
    void rowsWithoutTheNewColumnStillRead() {
        UUID tenantId = seedTenant("f2");
        UUID campaignId = seedCampaign(tenantId, null);
        UUID snapshotId = createSnapshot(campaignId, tenantId);

        // Simulate a row written before this phase: every other column intact,
        // the new column simply holding nothing.
        transactionTemplate.executeWithoutResult(tx -> entityManager
                .createNativeQuery("UPDATE campaign_execution_configurations"
                        + " SET integration_config = NULL WHERE id = :id")
                .setParameter("id", snapshotId)
                .executeUpdate());

        CampaignConfigurationSnapshot snapshot = reloadSnapshot(snapshotId);

        assertThat(snapshot.getIntegrationConfig()).isNull();
        assertThat(snapshot.asIntegrationConfig()).isEmpty();
        // The rest of the snapshot is untouched by the new column.
        assertThat(snapshot.getCampaignType()).isEqualTo(CampaignType.PLAYFILE);
        assertThat(snapshot.getTimezone()).isEqualTo("UTC");
    }

    // === helpers ===

    private record Pair(CampaignExecution execution, UUID snapshotId) {}

    /** Reads the persisted snapshot back in its own transaction. */
    private CampaignConfigurationSnapshot reloadSnapshot(UUID snapshotId) {
        return transactionTemplate.execute(tx -> snapshotRepository.findById(snapshotId)
                        .orElseThrow())
                .getConfiguration();
    }

    private UUID createSnapshot(UUID campaignId, UUID tenantId) {
        return transactionTemplate.execute(tx -> configurationService.createExecutionSnapshot(
                        campaignRepository
                                .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                                .orElseThrow()))
                .getId();
    }

    /** An execution row plus its frozen snapshot, as real creation would. */
    private Pair createExecutionWithSnapshot(UUID campaignId, UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            CampaignExecutionConfiguration snapshot =
                    configurationService.createExecutionSnapshot(campaignRepository
                            .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                            .orElseThrow());
            CampaignExecution e = new CampaignExecution();
            e.setCampaignId(campaignId);
            e.setTenantId(tenantId);
            e.setConfigurationSnapshotId(snapshot.getId());
            e.setRequestedAt(Instant.now());
            e.setRequestedBy(CALLER_ID.toString());
            return new Pair(executionRepository.saveAndFlush(e), snapshot.getId());
        });
    }

    /** The operator edit: rewrite the campaign's integration block. */
    private void mutateCampaign(UUID campaignId, UUID tenantId, String integrationJson) {
        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            c.setIntegrationConfig(integrationJson == null ? null : JSON.readTree(integrationJson));
            campaignRepository.saveAndFlush(c);
        });
    }

    private UUID seedTenant(String label) {
        return transactionTemplate.execute(tx -> {
            com.shivang.obd.tenant.TenantEntity t =
                    new com.shivang.obd.tenant.TenantEntity();
            t.setName("tenant-" + label + "-" + SEQ.incrementAndGet());
            t.setSlug("t-" + label + "-" + SEQ.incrementAndGet());
            t.setStatus(LifecycleStatus.ACTIVE);
            return tenantRepository.saveAndFlush(t).getId();
        });
    }

    private UUID seedCampaign(UUID tenantId, String integrationJson) {
        return transactionTemplate.execute(tx -> {
            com.shivang.obd.contact.ContactGroupEntity group =
                    new com.shivang.obd.contact.ContactGroupEntity();
            group.setTenantId(tenantId);
            group.setName("cg-" + SEQ.incrementAndGet());
            UUID groupId = contactGroupRepository.saveAndFlush(group).getId();

            CampaignEntity c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-vb7c3-" + SEQ.incrementAndGet());
            c.setCampaignType(CampaignType.PLAYFILE);
            c.setStatus(CampaignStatus.SCHEDULED);
            c.setContentMode(ContentMode.AUDIO);
            c.setAudioAssetId(seedApprovedAudioAsset(tenantId));
            c.setDidId(seedDid(tenantId));
            c.setContactGroupId(groupId);
            c.setSchedule(new ScheduleSpec(null, null, null, "UTC", null, null));
            c.setRetryPolicy(new RetryPolicySpec(0, null, RetryStrategy.FIXED));
            if (integrationJson != null) {
                c.setIntegrationConfig(JSON.readTree(integrationJson));
            }
            return campaignRepository.saveAndFlush(c).getId();
        });
    }

    private UUID seedDid(UUID tenantId) {
        com.shivang.obd.did.DidEntity d = new com.shivang.obd.did.DidEntity();
        d.setTenantId(tenantId);
        d.setE164Number("+9199" + String.format("%08d", SEQ.incrementAndGet()));
        d.setCountryCode("+91");
        d.setNumberType(com.shivang.obd.did.NumberType.MOBILE);
        d.setProvider("TATA");
        d.setStatus(DidStatus.ACTIVE);
        d.setAllocationState(AllocationState.ASSIGNED);
        d.setAllocationSource(com.shivang.obd.did.AllocationSource.PLATFORM);
        return didRepository.saveAndFlush(d).getId();
    }

    private UUID seedApprovedAudioAsset(UUID tenantId) {
        com.shivang.obd.audio.AudioAssetEntity a = new com.shivang.obd.audio.AudioAssetEntity();
        a.setTenantId(tenantId);
        a.setName("asset-" + SEQ.incrementAndGet());
        a.setFileName("asset-" + SEQ.incrementAndGet() + ".wav");
        a.setContentType("audio/wav");
        a.setFileSize(1024L);
        a.setStorageReference("s3://vb7c3/" + SEQ.incrementAndGet() + ".wav");
        a.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
        return audioAssetRepository.saveAndFlush(a).getId();
    }
}
