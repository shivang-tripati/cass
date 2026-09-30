package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.config.AgentSelectionStrategy;
import com.shivang.obd.campaign.config.ConnectByAgentCampaignConfig;
import com.shivang.obd.campaign.dto.ExecuteCampaignRequest;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.did.AllocationSource;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.agent.AgentQueueReferenceChecker;
import com.shivang.obd.voice.queue.Queue;
import com.shivang.obd.voice.queue.QueueReferenceService;
import com.shivang.obd.voice.queue.QueueRepository;
import com.shivang.obd.voice.queue.QueueStatus;
import jakarta.persistence.EntityManager;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * VB-7A: CONNECT_BY_AGENT configuration against real PostgreSQL.
 *
 * <p>What only a real database can prove for this phase:
 * <ul>
 *   <li><b>D13/D14</b> — the typed configuration survives a round trip through
 *       the existing {@code campaigns.type_config} and
 *       {@code campaign_execution_configurations.type_config} JSONB columns, and
 *       is frozen into the execution snapshot;</li>
 *   <li><b>D15</b> — editing the campaign's queue or ring window after an
 *       execution exists leaves that execution's snapshot untouched, and a later
 *       execution picks up the new values (no versioning, no shared snapshot);</li>
 *   <li><b>D16</b> — the runtime reads the snapshot, not the live campaign;</li>
 *   <li><b>B7/C10</b> — queue ownership and administrative lifecycle are decided
 *       by tenant-scoped SQL, and an INACTIVE queue makes a configured campaign
 *       unready without any agent ever being consulted.</li>
 * </ul>
 *
 * <p>Harness conventions follow the VB-4/5/6 series: static container startup,
 * {@code NOT_SUPPORTED} propagation, services constructed directly and invoked
 * inside a {@link TransactionTemplate}.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectByAgentConfigPostgresIntegrationTest {

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
    private QueueRepository queueRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final UUID CALLER_ID =
            UUID.fromString("ff000000-0000-4000-8000-0000000000f1");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger E164_SEQ = new AtomicInteger(8000);

    private CampaignExecutionService executionService;
    private CampaignConfigurationService configurationService;
    private CampaignReadinessService readinessService;
    private CampaignRuntimeConfigResolver runtimeConfigResolver;

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                            + startupFailure.getMessage(), startupFailure);
        }
        var allowAll = new com.shivang.obd.authz.AuthorizationService(
                List.of(), null, null, null, List.of()) {
            @Override
            public void requireCapability(UUID userId, String capabilityKey,
                    com.shivang.obd.authz.AccessCheck target) {
                // harness pass-through
            }
        };
        CurrentUserProvider currentUser = new CurrentUserProvider() {
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
        // The real queue seam over the real repository, wired into the real
        // canonical validator: this test must not be able to pass by mocking away
        // the tenant-scoped SQL it is meant to prove.
        AgentQueueReferenceChecker checker = new QueueReferenceService(queueRepository);
        var provider = new org.springframework.beans.factory.ObjectProvider<
                AgentQueueReferenceChecker>() {
            @Override
            public AgentQueueReferenceChecker getObject() {
                return checker;
            }

            @Override
            public AgentQueueReferenceChecker getObject(Object... args) {
                return checker;
            }

            @Override
            public AgentQueueReferenceChecker getIfAvailable() {
                return checker;
            }

            @Override
            public AgentQueueReferenceChecker getIfUnique() {
                return checker;
            }
        };
        CampaignResourceValidationService validator = new CampaignResourceValidationService(
                didRepository, audioAssetRepository, null, provider);
        readinessService = new CampaignReadinessService(
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
            entityManager.createQuery("DELETE FROM com.shivang.obd.contact.ContactGroupMemberEntity")
                    .executeUpdate();
            entityManager.createQuery("DELETE FROM ContactEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactGroupEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM DidEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM Queue").executeUpdate();
            entityManager.createQuery("DELETE FROM TenantEntity").executeUpdate();
        });
    }

    // === D. snapshot semantics ===

    @Test
    @DisplayName("D13. the typed CONNECT_BY_AGENT configuration is frozen into the execution "
            + "snapshot and round-trips through JSONB")
    void configurationIsFrozenIntoTheSnapshot() {
        UUID tenantId = seedTenant("d13").getId();
        UUID didId = seedDid(tenantId);
        UUID groupId = seedContactGroup(tenantId);
        UUID queueId = seedQueue(tenantId, QueueStatus.ACTIVE, "d13");
        UUID campaignId = seedConnectByAgentCampaign(tenantId, didId, groupId, queueId, 45);

        tenantScope(tenantId);
        var execution = transactionTemplate.execute(tx ->
                executionService.execute(campaignId, new ExecuteCampaignRequest(null)));
        assertThat(execution).isNotNull();

        CampaignExecutionConfiguration snapshot = transactionTemplate.execute(tx ->
                snapshotRepository.findById(execution.data().configurationSnapshotId()))
                .orElseThrow();
        assertThat(snapshot.getConfiguration().getCampaignType()).isEqualTo(CampaignType.CONNECT_BY_AGENT);

        // The JSONB round trip preserves every field exactly.
        JsonNode frozen = snapshot.getConfiguration().getTypeConfig()
                .get(ConnectByAgentCampaignConfig.KEY);
        assertThat(frozen).isNotNull();
        assertThat(frozen.get("queueId").asText()).isEqualTo(queueId.toString());
        assertThat(frozen.get("selectionStrategy").asText())
                .isEqualTo(AgentSelectionStrategy.LEAST_ACTIVE_RESERVATIONS.name());
        assertThat(frozen.get("ringDurationSeconds").asInt()).isEqualTo(45);

        // ... and it parses back into the same typed configuration the runtime uses.
        var parsed = (ConnectByAgentCampaignConfig)
                com.shivang.obd.campaign.config.CampaignTypeConfig.fromTypeConfig(
                        CampaignType.CONNECT_BY_AGENT,
                        snapshot.getConfiguration().getTypeConfig());
        assertThat(parsed.queueId()).isEqualTo(queueId);
        assertThat(parsed.effectiveRingSeconds()).isEqualTo(45);
    }

    @Test
    @DisplayName("D14/D15. editing the campaign queue and ring window after an execution exists "
            + "leaves that snapshot untouched; a later execution gets the new values")
    void campaignEditDoesNotMutateAnExistingSnapshot() {
        UUID tenantId = seedTenant("d15").getId();
        UUID didId = seedDid(tenantId);
        UUID groupId = seedContactGroup(tenantId);
        UUID queueV1 = seedQueue(tenantId, QueueStatus.ACTIVE, "d15a");
        UUID queueV2 = seedQueue(tenantId, QueueStatus.ACTIVE, "d15b");
        UUID campaignId = seedConnectByAgentCampaign(tenantId, didId, groupId, queueV1, 30);

        tenantScope(tenantId);
        var e1 = transactionTemplate.execute(tx ->
                executionService.execute(campaignId, new ExecuteCampaignRequest(null)));
        UUID snapshotIdV1 = e1.data().configurationSnapshotId();

        // Edit the campaign: different queue, different ring window.
        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            c.setTypeConfig(connectByAgentJson(queueV2, 90));
            campaignRepository.saveAndFlush(c);
        });

        // The already-created execution still reads the values it was frozen with.
        var config = runtimeConfigResolver.resolve(transactionTemplate.execute(tx ->
                executionRepository.findByIdAndDeletedAtIsNull(
                        e1.data().id())).orElseThrow());
        assertThat(config.asConnectByAgent()).isPresent();
        assertThat(config.asConnectByAgent().orElseThrow().queueId()).isEqualTo(queueV1);
        assertThat(config.asConnectByAgent().orElseThrow().effectiveRingSeconds()).isEqualTo(30);
        assertThat(config.agentConnectRequest().queueId()).isEqualTo(queueV1);
        assertThat(config.agentConnectRequest().effectiveRingSeconds()).isEqualTo(30);

        // A later execution picks up the edit, in its own snapshot.
        settle(e1.data().id());

        var e2 = transactionTemplate.execute(tx ->
                executionService.execute(campaignId, new ExecuteCampaignRequest(null)));
        assertThat(e2.data().configurationSnapshotId()).isNotEqualTo(snapshotIdV1);

        CampaignExecutionConfiguration s2 = transactionTemplate.execute(tx ->
                snapshotRepository.findById(e2.data().configurationSnapshotId())).orElseThrow();
        JsonNode frozen = s2.getConfiguration().getTypeConfig()
                .get(ConnectByAgentCampaignConfig.KEY);
        assertThat(frozen.get("queueId").asText()).isEqualTo(queueV2.toString());
        assertThat(frozen.get("ringDurationSeconds").asInt()).isEqualTo(90);

        // The first snapshot row is byte-for-byte unchanged.
        CampaignExecutionConfiguration s1 = transactionTemplate.execute(tx ->
                snapshotRepository.findById(snapshotIdV1)).orElseThrow();
        assertThat(s1.getConfiguration().getTypeConfig()
                .get(ConnectByAgentCampaignConfig.KEY).get("queueId").asText())
                .isEqualTo(queueV1.toString());
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
        transactionTemplate.executeWithoutResult(tx -> {
            CampaignExecution e = executionRepository
                    .findByIdAndDeletedAtIsNull(executionId).orElseThrow();
            e.setStatus(CampaignExecutionStatus.COMPLETED);
            executionRepository.saveAndFlush(e);
        });
    }

    @Test
    @DisplayName("D16. the runtime request is built from the snapshot, so a campaign edit cannot "
            + "redirect a call that is already running")
    void runtimeRequestComesFromTheSnapshot() {
        UUID tenantId = seedTenant("d16").getId();
        UUID didId = seedDid(tenantId);
        UUID groupId = seedContactGroup(tenantId);
        UUID queueId = seedQueue(tenantId, QueueStatus.ACTIVE, "d16");
        UUID campaignId = seedConnectByAgentCampaign(tenantId, didId, groupId, queueId, 60);

        tenantScope(tenantId);
        var e1 = transactionTemplate.execute(tx ->
                executionService.execute(campaignId, new ExecuteCampaignRequest(null)));

        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            c.setTypeConfig(connectByAgentJson(queueId, 15));
            campaignRepository.saveAndFlush(c);
        });

        var config = runtimeConfigResolver.resolve(transactionTemplate.execute(tx ->
                executionRepository.findByIdAndDeletedAtIsNull(
                        e1.data().id())).orElseThrow());
        assertThat(config.agentConnectRequest().effectiveRingSeconds()).isEqualTo(60);
    }

    // === C. readiness and queue lifecycle ===

    @Test
    @DisplayName("C9. a configured, ACTIVE, same-tenant queue makes the campaign ready")
    void activeQueueIsReady() {
        UUID tenantId = seedTenant("c9").getId();
        UUID queueId = seedQueue(tenantId, QueueStatus.ACTIVE, "c9");
        UUID campaignId = seedConnectByAgentCampaign(
                tenantId, seedDid(tenantId), seedContactGroup(tenantId), queueId, 60);

        var response = readiness(campaignId);
        assertThat(response.reasons()).noneMatch(
                r -> r.code().startsWith("AGENT_QUEUE") || r.code().startsWith("INVALID_AGENT"));
    }

    @Test
    @DisplayName("C10. an INACTIVE queue makes the campaign unready with AGENT_QUEUE_NOT_ACTIVE, "
            + "without any agent being consulted")
    void inactiveQueueBlocksReadiness() {
        UUID tenantId = seedTenant("c10").getId();
        UUID queueId = seedQueue(tenantId, QueueStatus.ACTIVE, "c10");
        UUID campaignId = seedConnectByAgentCampaign(
                tenantId, seedDid(tenantId), seedContactGroup(tenantId), queueId, 60);

        transactionTemplate.executeWithoutResult(tx -> {
            Queue q = queueRepository.findById(queueId).orElseThrow();
            q.setStatus(QueueStatus.INACTIVE);
            queueRepository.saveAndFlush(q);
        });

        var response = readiness(campaignId);
        assertThat(response.ready()).isFalse();
        assertThat(response.reasons())
                .anyMatch(r -> r.code().equals("AGENT_QUEUE_NOT_ACTIVE"));
    }

    @Test
    @DisplayName("B7. a foreign-tenant queue makes the campaign unready and is reported exactly "
            + "as a nonexistent one — no cross-tenant leak")
    void foreignQueueIsReportedAsUnavailable() {
        UUID tenantA = seedTenant("b7a").getId();
        UUID tenantB = seedTenant("b7b").getId();
        UUID foreignQueue = seedQueue(tenantB, QueueStatus.ACTIVE, "b7");
        UUID missingQueue = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

        UUID campaignA = seedConnectByAgentCampaign(
                tenantA, seedDid(tenantA), seedContactGroup(tenantA), foreignQueue, 60);
        UUID campaignB = seedConnectByAgentCampaign(
                tenantA, seedDid(tenantA), seedContactGroup(tenantA), missingQueue, 60);

        var foreign = readiness(campaignA);
        var missing = readiness(campaignB);

        assertThat(foreign.ready()).isFalse();
        assertThat(foreign.reasons())
                .anyMatch(r -> r.code().equals("AGENT_QUEUE_NOT_AVAILABLE"));
        // Indistinguishable: the same code, and the same message.
        assertThat(foreign.reasons().stream()
                .filter(r -> r.code().equals("AGENT_QUEUE_NOT_AVAILABLE")).map(r -> r.message())
                .toList())
                .isEqualTo(missing.reasons().stream()
                        .filter(r -> r.code().equals("AGENT_QUEUE_NOT_AVAILABLE"))
                        .map(r -> r.message()).toList());
    }

    @Test
    @DisplayName("A2b. an invalid stored configuration makes the campaign unready")
    void invalidStoredConfigurationBlocksReadiness() {
        UUID tenantId = seedTenant("a2b").getId();
        UUID queueId = seedQueue(tenantId, QueueStatus.ACTIVE, "a2b");
        UUID campaignId = seedConnectByAgentCampaign(
                tenantId, seedDid(tenantId), seedContactGroup(tenantId), queueId, 60);

        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            // A queue-less payload: the VB-6A placeholder shape is no longer valid.
            c.setTypeConfig(toJson("{\"legacy\": {\"anything\": true}}"));
            campaignRepository.saveAndFlush(c);
        });

        var response = readiness(campaignId);
        assertThat(response.ready()).isFalse();
        assertThat(response.reasons())
                .anyMatch(r -> r.code().equals("INVALID_AGENT_CONFIGURATION"));
    }

    @Test
    @DisplayName("C9b. a campaign whose queue was disabled after it was scheduled cannot start a "
            + "new execution")
    void disabledQueueBlocksExecutionCreation() {
        UUID tenantId = seedTenant("c9b").getId();
        UUID queueId = seedQueue(tenantId, QueueStatus.ACTIVE, "c9b");
        UUID campaignId = seedConnectByAgentCampaign(
                tenantId, seedDid(tenantId), seedContactGroup(tenantId), queueId, 60);

        tenantScope(tenantId);
        // While the queue is ACTIVE, an execution can be created.
        com.shivang.obd.common.api.response.ApiResponse<
                com.shivang.obd.campaign.dto.CampaignExecutionResponse> created =
                transactionTemplate.execute(tx ->
                        executionService.execute(campaignId, new ExecuteCampaignRequest(null)));
        assertThat(created).isNotNull();
        assertThat(created.data().configurationSnapshotId()).isNotNull();

        transactionTemplate.executeWithoutResult(tx -> {
            Queue q = queueRepository.findById(queueId).orElseThrow();
            q.setStatus(QueueStatus.DISABLED);
            queueRepository.saveAndFlush(q);
        });

        assertThatThrownBy(() -> transactionTemplate.execute(tx ->
                executionService.execute(campaignId, new ExecuteCampaignRequest(null))))
                .hasMessageContaining("AGENT_QUEUE_NOT_ACTIVE")
                .hasMessageContaining("agent queue is not active");
    }

    // === seeds ===

    private CampaignEntity campaign(UUID tenantId, UUID campaignId) {
        return transactionTemplate.execute(tx ->
                campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                        .orElseThrow());
    }

    /**
     * Readiness through the real public entry point, with the campaign's own
     * tenant as the caller scope. The harness authorization is a pass-through,
     * so this exercises the genuine visibility and reason-mapping path.
     */
    private com.shivang.obd.campaign.dto.CampaignReadinessResponse readiness(UUID campaignId) {
        UUID tenantId = transactionTemplate.execute(tx ->
                campaignRepository.findById(campaignId).orElseThrow().getTenantId());
        tenantScope(tenantId);
        return readinessService.evaluate(campaignId);
    }

    private void tenantScope(UUID tenantId) {
        com.shivang.obd.authz.context.OrganizationContextHolder
                .setAuthenticated(CALLER_ID, tenantId, null);
    }

    private TenantEntity seedTenant(String label) {
        return transactionTemplate.execute(tx -> {
            TenantEntity t = new TenantEntity();
            t.setName("tenant-" + label + "-" + SEQ.incrementAndGet());
            t.setSlug("t-" + label + "-" + SEQ.incrementAndGet());
            t.setStatus(LifecycleStatus.ACTIVE);
            return tenantRepository.saveAndFlush(t);
        });
    }

    private UUID seedDid(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            DidEntity d = new DidEntity();
            d.setE164Number("+9199" + String.format("%08d", E164_SEQ.incrementAndGet()));
            d.setCountryCode("+91");
            d.setNumberType(com.shivang.obd.did.NumberType.MOBILE);
            d.setProvider("TATA");
            d.setStatus(DidStatus.ACTIVE);
            d.setAllocationState(AllocationState.ASSIGNED);
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

    private UUID seedQueue(UUID tenantId, QueueStatus status, String label) {
        return transactionTemplate.execute(tx -> {
            Queue q = new Queue();
            q.setTenantId(tenantId);
            q.setName("q-" + label + "-" + SEQ.incrementAndGet());
            q.setStatus(status);
            return queueRepository.saveAndFlush(q).getId();
        });
    }

    private UUID seedConnectByAgentCampaign(
            UUID tenantId, UUID didId, UUID groupId, UUID queueId, int ringSeconds) {
        return transactionTemplate.execute(tx -> {
            CampaignEntity c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-vb7a-" + SEQ.incrementAndGet());
            c.setCampaignType(CampaignType.CONNECT_BY_AGENT);
            c.setStatus(CampaignStatus.SCHEDULED);
            c.setDidId(didId);
            c.setContactGroupId(groupId);
        // VB-7C.1: a campaign with no execution timezone cannot be dialled at all -
        // OutboundDialService passes the snapshot zone to DailyDialLimitService,
        // which throws ExecutionTimezoneInvalidException (PERMANENT) on a null or
        // blank zone, with no JVM/UTC fallback. Readiness therefore requires a
        // timezone for every campaign. A windowless schedule is the minimal way
        // to satisfy it and keeps these tests scoped to their own dimension.
        c.setSchedule(new ScheduleSpec(null, null, null, "Asia/Kolkata", null, null));
            c.setRetryPolicy(new RetryPolicySpec(0, null, RetryStrategy.FIXED));
            c.setTypeConfig(connectByAgentJson(queueId, ringSeconds));
            return campaignRepository.saveAndFlush(c).getId();
        });
    }

    private static JsonNode connectByAgentJson(UUID queueId, int ringSeconds) {
        return toJson("{\"" + ConnectByAgentCampaignConfig.KEY + "\": {"
                + "\"queueId\": \"" + queueId + "\", "
                + "\"selectionStrategy\": \"" + AgentSelectionStrategy.LEAST_ACTIVE_RESERVATIONS
                + "\", \"ringDurationSeconds\": " + ringSeconds + "}}");
    }

    private static JsonNode toJson(String json) {
        return JsonMapper.builder().build().readTree(json);
    }
}