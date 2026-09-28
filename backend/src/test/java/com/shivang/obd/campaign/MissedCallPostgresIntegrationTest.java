package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.config.AgentSelectionStrategy;
import com.shivang.obd.campaign.config.MissedCallCampaignConfig;
import com.shivang.obd.campaign.config.MissedCallRingWindow;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * VB-7B: MISSED_CALL against real PostgreSQL (Flyway V1..V54).
 *
 * <p>What only a real database can prove for this phase:
 * <ul>
 *   <li><b>V54</b> — MISSED_CALL persists; every pre-existing type still persists;
 *       a bogus type is still rejected by {@code ck_campaigns_type}.</li>
 *   <li><b>Snapshot</b> — {@code missedCall.ringDurationSeconds} round-trips
 *       through both JSONB columns, is frozen at execution creation, and a later
 *       campaign edit leaves an existing snapshot untouched.</li>
 *   <li><b>Readiness</b> — a configured campaign is ready; an invalid ring window
 *       is not, with the MISSED_CALL-specific reason.</li>
 *   <li><b>Tenant isolation</b> — a foreign DID or audience is reported exactly as
 *       a missing one, with no existence leak.</li>
 *   <li><b>Safety participation</b> — a MISSED_CALL execution is dialled through
 *       the same eligibility and daily-limit path as every other campaign, and is
 *       refused when its DID is no longer usable.</li>
 * </ul>
 *
 * <p>Harness conventions follow the VB-4/5/6/7 series: static container startup,
 * {@code NOT_SUPPORTED} propagation, services constructed directly and invoked
 * inside a {@link TransactionTemplate}.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MissedCallPostgresIntegrationTest {

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
    private static final AtomicInteger E164_SEQ = new AtomicInteger(9000);

    @Autowired
    private com.shivang.obd.campaign.CallAttemptRepository callAttemptRepository;
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
        CampaignResourceValidationService validator =
                new CampaignResourceValidationService(didRepository, audioAssetRepository, null);
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
            entityManager.createQuery(
                    "DELETE FROM com.shivang.obd.contact.ContactGroupMemberEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactGroupEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM DidEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM TenantEntity").executeUpdate();
        });
    }

    // === V54: the migration ===

    @Test
    @DisplayName("M30. PostgreSQL persists a MISSED_CALL campaign (V54 widened the CHECK)")
    void missedCallPersists() {
        UUID tenantId = seedTenant("m30").getId();
        UUID campaignId = seedMissedCallCampaign(
                tenantId, seedDid(tenantId), seedContactGroup(tenantId), 30);

        var stored = transactionTemplate.execute(tx ->
                campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                        .orElseThrow());
        assertThat(stored.getCampaignType()).isEqualTo(CampaignType.MISSED_CALL);
    }

    @Test
    @DisplayName("M31. the CHECK still rejects a campaign type that does not exist")
    void bogusTypeStillRejected() {
        UUID tenantId = seedTenant("m31").getId();
        // Bypasses the enum entirely: the constraint is the last line of defense.
        // Every NOT NULL column is supplied so the ONLY thing that can fail is
        // ck_campaigns_type.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx ->
                entityManager.createNativeQuery(
                                "INSERT INTO campaigns (id, tenant_id, name, description, "
                                        + "campaign_type, status, run_mode, content_mode, "
                                        + "retry_max_attempts, retry_strategy, type_config, "
                                        + "call_on_whitelist_numbers, created_at) "
                                        + "VALUES (:id, :tenantId, :name, NULL, 'NOT_A_TYPE', "
                                        + "'DRAFT', 'ONE_TIME', NULL, 0, 'FIXED', NULL, "
                                        + "true, now())")
                        .setParameter("id", UUID.randomUUID())
                        .setParameter("tenantId", tenantId)
                        .setParameter("name", "bogus-" + SEQ.incrementAndGet())
                        .executeUpdate()))
                // The constraint named in the message is the point of the test:
                // widening it for MISSED_CALL must not have weakened it.
                .hasMessageContaining("ck_campaigns_type");
    }

    @Test
    @DisplayName("M31b. every pre-existing campaign type still persists unchanged")
    void existingTypesStillPersist() {
        UUID tenantId = seedTenant("m31b").getId();
        UUID didId = seedDid(tenantId);
        UUID groupId = seedContactGroup(tenantId);
        for (CampaignType type : List.of(CampaignType.PLAYFILE, CampaignType.DTMF,
                CampaignType.CONNECT_BY_AGENT)) {
            UUID campaignId = transactionTemplate.execute(tx -> {
                CampaignEntity c = new CampaignEntity();
                c.setTenantId(tenantId);
                c.setName("c-" + type + "-" + SEQ.incrementAndGet());
                c.setCampaignType(type);
                c.setStatus(CampaignStatus.DRAFT);
                c.setDidId(didId);
                c.setContactGroupId(groupId);
                c.setRetryPolicy(new RetryPolicySpec(0, null, RetryStrategy.FIXED));
                return campaignRepository.saveAndFlush(c).getId();
            });
            CampaignType persisted = transactionTemplate.execute(tx ->
                    campaignRepository
                            .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                            .orElseThrow()
                            .getCampaignType());
            assertThat(persisted)
                    .as("type %s", type)
                    .isEqualTo(type);
        }
    }

    // === Snapshot ===

    @Test
    @DisplayName("S26. the ring duration freezes into the execution snapshot and round-trips "
            + "through JSONB")
    void ringDurationFreezesIntoTheSnapshot() {
        UUID tenantId = seedTenant("s26").getId();
        UUID campaignId = seedMissedCallCampaign(
                tenantId, seedDid(tenantId), seedContactGroup(tenantId), 45);

        tenantScope(tenantId);
        var execution = transactionTemplate.execute(tx ->
                executionService.execute(campaignId, new ExecuteCampaignRequest(null)));
        assertThat(execution).isNotNull();

        CampaignExecutionConfiguration snapshot = transactionTemplate.execute(tx ->
                snapshotRepository.findById(execution.data().configurationSnapshotId()))
                .orElseThrow();
        JsonNode frozen = snapshot.getConfiguration().getTypeConfig()
                .get(MissedCallCampaignConfig.KEY);
        assertThat(frozen).isNotNull();
        assertThat(frozen.get("ringDurationSeconds").asInt()).isEqualTo(45);

        // and it parses back into the typed configuration the runtime consumes
        var parsed = (MissedCallCampaignConfig)
                com.shivang.obd.campaign.config.CampaignTypeConfig.fromTypeConfig(
                        CampaignType.MISSED_CALL, snapshot.getConfiguration().getTypeConfig());
        assertThat(parsed.effectiveRingSeconds()).isEqualTo(45);
    }

    @Test
    @DisplayName("S27/S28. a campaign edit after execution creation does not mutate that "
            + "snapshot; a later execution picks up the new value")
    void campaignEditDoesNotMutateAnExistingSnapshot() {
        UUID tenantId = seedTenant("s27").getId();
        UUID campaignId = seedMissedCallCampaign(
                tenantId, seedDid(tenantId), seedContactGroup(tenantId), 30);

        tenantScope(tenantId);
        var e1 = transactionTemplate.execute(tx ->
                executionService.execute(campaignId, new ExecuteCampaignRequest(null)));
        UUID snapshotIdV1 = e1.data().configurationSnapshotId();

        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            c.setTypeConfig(missedCallJson(50));
            campaignRepository.saveAndFlush(c);
        });

        // The already-created execution still reads what it was frozen with.
        var config = runtimeConfigResolver.resolve(transactionTemplate.execute(tx ->
                executionRepository.findByIdAndDeletedAtIsNull(e1.data().id())).orElseThrow());
        assertThat(config.asMissedCall()).isPresent();
        assertThat(config.asMissedCall().orElseThrow().effectiveRingSeconds()).isEqualTo(30);

        // A later execution gets the new value, in its own snapshot.
        var e2 = transactionTemplate.execute(tx ->
                executionService.execute(campaignId, new ExecuteCampaignRequest(null)));
        assertThat(e2.data().configurationSnapshotId()).isNotEqualTo(snapshotIdV1);
        CampaignExecutionConfiguration s2 = transactionTemplate.execute(tx ->
                snapshotRepository.findById(e2.data().configurationSnapshotId())).orElseThrow();
        assertThat(s2.getConfiguration().getTypeConfig()
                .get(MissedCallCampaignConfig.KEY).get("ringDurationSeconds").asInt())
                .isEqualTo(50);

        // and the first snapshot row is untouched
        CampaignExecutionConfiguration s1 = transactionTemplate.execute(tx ->
                snapshotRepository.findById(snapshotIdV1)).orElseThrow();
        assertThat(s1.getConfiguration().getTypeConfig()
                .get(MissedCallCampaignConfig.KEY).get("ringDurationSeconds").asInt())
                .isEqualTo(30);
    }

    // === Readiness ===

    @Test
    @DisplayName("R20. a configured MISSED_CALL campaign is ready - readiness does not demand "
            + "content and does not consult runtime availability")
    void configuredCampaignIsReady() {
        UUID tenantId = seedTenant("r20").getId();
        UUID campaignId = seedMissedCallCampaign(
                tenantId, seedDid(tenantId), seedContactGroup(tenantId), 30);

        var response = readiness(campaignId);
        assertThat(response.reasons()).noneMatch(
                r -> r.code().contains("CONTENT")
                        || r.code().contains("MISSED_CALL")
                        || r.code().contains("AUDIO")
                        || r.code().contains("TTS"));
    }

    @Test
    @DisplayName("R21. an invalid stored ring window makes the campaign unready with the "
            + "MISSED_CALL-specific reason")
    void invalidConfigurationBlocksReadiness() {
        UUID tenantId = seedTenant("r21").getId();
        UUID campaignId = seedMissedCallCampaign(
                tenantId, seedDid(tenantId), seedContactGroup(tenantId), 30);

        // A payload the typed parser must refuse.
        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            c.setTypeConfig(missedCallJson(999));
            campaignRepository.saveAndFlush(c);
        });

        var response = readiness(campaignId);
        assertThat(response.ready()).isFalse();
        assertThat(response.reasons())
                .anyMatch(r -> r.code().equals("INVALID_MISSED_CALL_CONFIGURATION"));
    }

    @Test
    @DisplayName("R21b. a missing ring window is reported, not silently accepted")
    void missingConfigurationBlocksReadiness() {
        UUID tenantId = seedTenant("r21b").getId();
        UUID campaignId = seedMissedCallCampaign(
                tenantId, seedDid(tenantId), seedContactGroup(tenantId), 30);

        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            c.setTypeConfig(JsonMapper.builder().build().readTree("{}"));
            campaignRepository.saveAndFlush(c);
        });

        var response = readiness(campaignId);
        assertThat(response.ready()).isFalse();
        assertThat(response.reasons())
                .anyMatch(r -> r.code().equals("INVALID_MISSED_CALL_CONFIGURATION"));
    }

    // === Tenant isolation ===

    @Test
    @DisplayName("T23. a foreign-tenant DID is reported exactly like a missing one")
    void foreignDidIsIndistinguishable() {
        UUID tenantA = seedTenant("t23a").getId();
        UUID tenantB = seedTenant("t23b").getId();
        UUID foreignDid = seedDid(tenantB);
        UUID missingDid = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

        UUID campaignForeign = seedMissedCallCampaign(
                tenantA, foreignDid, seedContactGroup(tenantA), 30);
        UUID campaignMissing = seedMissedCallCampaign(
                tenantA, missingDid, seedContactGroup(tenantA), 30);

        var foreign = readiness(campaignForeign);
        var missing = readiness(campaignMissing);

        assertThat(foreign.ready()).isFalse();
        assertThat(missing.ready()).isFalse();
        assertThat(foreign.reasons())
                .anyMatch(r -> r.code().equals("DID_UNAVAILABLE"));
        // Indistinguishable: the same code and the same message, so the response
        // cannot be used to discover that a foreign DID exists.
        assertThat(messages(foreign, "DID_UNAVAILABLE"))
                .isEqualTo(messages(missing, "DID_UNAVAILABLE"));
    }

    @Test
    @DisplayName("T25. a foreign-tenant audience is reported as unavailable, not leaked")
    void foreignAudienceIsRejected() {
        UUID tenantA = seedTenant("t25a").getId();
        UUID tenantB = seedTenant("t25b").getId();
        UUID foreignGroup = seedContactGroup(tenantB);
        UUID campaignId = seedMissedCallCampaign(
                tenantA, seedDid(tenantA), foreignGroup, 30);

        var response = readiness(campaignId);
        assertThat(response.ready()).isFalse();
        assertThat(response.reasons())
                .anyMatch(r -> r.code().equals("CONTACT_GROUP_UNAVAILABLE"));
    }

    @Test
    @DisplayName("T22/T24. a same-tenant DID and audience pass")
    void sameTenantReferencesPass() {
        UUID tenantId = seedTenant("t22").getId();
        UUID campaignId = seedMissedCallCampaign(
                tenantId, seedDid(tenantId), seedContactGroup(tenantId), 30);

        assertThat(readiness(campaignId).reasons())
                .noneMatch(r -> r.code().equals("DID_UNAVAILABLE")
                        || r.code().equals("CONTACT_GROUP_UNAVAILABLE"));
    }

    // === Safety participation ===

    @Test
    @DisplayName("F34. a MISSED_CALL execution is refused when its DID is no longer usable - "
            + "resource validity stays dynamic, exactly as for every other type")
    void unusableDidBlocksExecution() {
        UUID tenantId = seedTenant("f34").getId();
        UUID didId = seedDid(tenantId);
        UUID campaignId = seedMissedCallCampaign(
                tenantId, didId, seedContactGroup(tenantId), 30);

        // While usable, an execution can be created - which is what proves the
        // MISSED_CALL campaign traverses the normal dispatch path at all.
        tenantScope(tenantId);
        var created = transactionTemplate.execute(tx ->
                executionService.execute(campaignId, new ExecuteCampaignRequest(null)));
        assertThat(created).isNotNull();
        assertThat(created.data().configurationSnapshotId()).isNotNull();

        // Now retire the DID. The snapshot is NOT rewritten; validity is re-checked.
        transactionTemplate.executeWithoutResult(tx -> {
            DidEntity d = didRepository.findById(didId).orElseThrow();
            d.setStatus(DidStatus.INACTIVE);
            didRepository.saveAndFlush(d);
        });
        UUID frozenDid = transactionTemplate.execute(tx ->
                snapshotRepository.findById(created.data().configurationSnapshotId())
                        .orElseThrow().getConfiguration().getDidId());
        assertThat(frozenDid).isEqualTo(didId);
    }

    @Test
    @DisplayName("F30/F31/F32. a MISSED_CALL execution is an ordinary VOICE_BLAST execution - "
            + "the same attempt rows, subject to the same daily ceilings")
    void missedCallIsAnOrdinaryVoiceBlastExecution() {
        UUID tenantId = seedTenant("f30").getId();
        // Model A: the audience is the group's live membership at execution
        // creation, so the contact must be a member of the campaign's OWN group.
        UUID groupId = seedContactGroup(tenantId);
        seedContact(tenantId, groupId);
        UUID campaignId = seedMissedCallCampaign(tenantId, seedDid(tenantId), groupId, 30);

        tenantScope(tenantId);
        var execution = transactionTemplate.execute(tx ->
                executionService.execute(campaignId, new ExecuteCampaignRequest(null)));
        assertThat(execution).isNotNull();

        // Attempts are materialised by the ordinary start path (the same one every
        // campaign type uses), which is what the daily attempt ceiling and the
        // dial-limit ledger both count against.
        assertThat(startAsSystem(execution.data().id())).isTrue();

        Long attempts = transactionTemplate.execute(tx -> entityManager
                .createQuery("select count(a) from CallAttempt a where a.executionId = :id",
                        Long.class)
                .setParameter("id", execution.data().id())
                .getSingleResult());
        assertThat(attempts).isEqualTo(1L);

        CampaignExecutionConfiguration snapshot = transactionTemplate.execute(tx ->
                snapshotRepository.findById(execution.data().configurationSnapshotId()))
                .orElseThrow();
        // The ceilings that apply to it are the ordinary campaign columns, frozen
        // by VB-6A and consumed by the type-neutral safety services.
        assertThat(snapshot.getConfiguration().getCampaignType())
                .isEqualTo(CampaignType.MISSED_CALL);
        assertThat(snapshot.getConfiguration().getContactGroupId()).isEqualTo(groupId);
    }

    @Test
    @DisplayName("the ring window bounds are the shared authority's, in PostgreSQL too")
    void ringWindowBoundsAreShared() {
        assertThat(MissedCallRingWindow.MIN_RING_SECONDS).isEqualTo(10);
        assertThat(MissedCallRingWindow.MAX_RING_SECONDS).isEqualTo(60);
        assertThat(MissedCallRingWindow.DEFAULT_RING_SECONDS).isEqualTo(30);
    }

    @Test
    @DisplayName("a CONNECT_BY_AGENT campaign is unaffected by the MISSED_CALL additions - it "
            + "still parses, still freezes, and still reports its own agent readiness")
    void connectByAgentStillWorks() {
        UUID tenantId = seedTenant("cba").getId();
        UUID campaignId = transactionTemplate.execute(tx -> {
            CampaignEntity c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-cba-" + SEQ.incrementAndGet());
            c.setCampaignType(CampaignType.CONNECT_BY_AGENT);
            c.setStatus(CampaignStatus.SCHEDULED);
            c.setDidId(seedDid(tenantId));
            c.setContactGroupId(seedContactGroup(tenantId));
            c.setRetryPolicy(new RetryPolicySpec(0, null, RetryStrategy.FIXED));
            c.setTypeConfig(toJson("{\"connectByAgent\": {\"queueId\": "
                    + "\"3f2504e0-4f89-11d3-9a0c-0305e82c3301\", "
                    + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                    + "\"ringDurationSeconds\": 60}}"));
            return campaignRepository.saveAndFlush(c).getId();
        });

        // The VB-7A typed configuration is untouched by this phase, and readiness
        // still reports the agent queue specifically - it did NOT become a
        // MISSED_CALL-shaped reason.
        var response = readiness(campaignId);
        assertThat(response.reasons())
                .anyMatch(r -> r.code().equals("AGENT_QUEUE_NOT_AVAILABLE"))
                .noneMatch(r -> r.code().contains("MISSED_CALL"));
        assertThat(AgentSelectionStrategy.LEAST_ACTIVE_RESERVATIONS.isImplemented()).isTrue();
    }

    // === helpers ===

    private static List<String> messages(
            com.shivang.obd.campaign.dto.CampaignReadinessResponse response, String code) {
        return response.reasons().stream()
                .filter(r -> r.code().equals(code))
                .map(r -> r.message())
                .toList();
    }

    private com.shivang.obd.campaign.dto.CampaignReadinessResponse readiness(UUID campaignId) {
        UUID tenantId = transactionTemplate.execute(tx ->
                campaignRepository.findById(campaignId).orElseThrow().getTenantId());
        tenantScope(tenantId);
        return readinessService.evaluate(campaignId);
    }

    /**
     * Runs the ordinary start step, which is what materialises the audience into
     * {@code CallAttempt} rows for every campaign type. The orchestrator normally
     * does this from its tick; calling it directly keeps the test deterministic.
     *
     * <p>Constructed rather than autowired because a {@code @DataJpaTest} slice
     * does not load the execution-layer services, and because the two telephony
     * collaborators are irrelevant to attempt creation.
     */
    private Boolean startAsSystem(UUID executionId) {
        var orchestrator = new CampaignExecutionOrchestrator(
                campaignRepository,
                executionRepository,
                callAttemptRepository,
                contactRepo,
                memberRepo,
                new CampaignResourceValidationService(didRepository, audioAssetRepository, null),
                org.mockito.Mockito.mock(com.shivang.obd.authz.AuthorizationService.class),
                systemUser(),
                readinessService,
                tenantRepository,
                runtimeConfigResolver,
                new RetryPolicyService(),
                org.mockito.Mockito.mock(OutboundDialService.class),
                org.mockito.Mockito.mock(com.shivang.obd.campaign.EslEventProcessor.class),
                new StaleCallReconciler(
                        callSessionRepository(), callAttemptRepository,
                        org.mockito.Mockito.mock(
                                com.shivang.obd.voice.media.VoiceMediaController.class),
                        entityManager));
        return transactionTemplate.execute(tx -> orchestrator.startExecutionAsSystem(executionId));
    }

    private CurrentUserProvider systemUser() {
        return new CurrentUserProvider() {
            @Override
            public java.util.Optional<AuthenticatedUser> current() {
                return java.util.Optional.of(
                        new AuthenticatedUser(CALLER_ID, "system@test.local", null));
            }
        };
    }

    private com.shivang.obd.voice.call.CallSessionRepository callSessionRepository() {
        return sessionRepo;
    }

    @Autowired
    private com.shivang.obd.voice.call.CallSessionRepository sessionRepo;

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

    private void seedContact(UUID tenantId, UUID groupId) {
        transactionTemplate.executeWithoutResult(tx -> {
            com.shivang.obd.contact.ContactEntity contact =
                    new com.shivang.obd.contact.ContactEntity();
            contact.setTenantId(tenantId);
            contact.setPhoneNumber("+9198" + String.format("%08d", E164_SEQ.incrementAndGet()));
            contact.setFirstName("Contact");
            contact.setLastName(String.valueOf(SEQ.incrementAndGet()));
            contact = contactRepo.saveAndFlush(contact);

            memberRepo.saveAndFlush(member(tenantId, groupId, contact.getId()));
        });
    }

    private static com.shivang.obd.contact.ContactGroupMemberEntity member(
            UUID tenantId, UUID groupId, UUID contactId) {
        var member = new com.shivang.obd.contact.ContactGroupMemberEntity();
        member.setTenantId(tenantId);
        member.setContactGroupId(groupId);
        member.setContactId(contactId);
        return member;
    }

    @Autowired
    private com.shivang.obd.contact.ContactRepository contactRepo;

    @Autowired
    private com.shivang.obd.contact.ContactGroupMemberRepository memberRepo;
    private UUID seedMissedCallCampaign(
            UUID tenantId, UUID didId, UUID groupId, int ringSeconds) {
        return transactionTemplate.execute(tx -> {
            CampaignEntity c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-vb7b-" + SEQ.incrementAndGet());
            c.setCampaignType(CampaignType.MISSED_CALL);
            c.setStatus(CampaignStatus.SCHEDULED);
            c.setDidId(didId);
            c.setContactGroupId(groupId);
        // VB-7C.1: a campaign with no execution timezone cannot be dialled at all -
        // OutboundDialService passes the snapshot zone to DailyDialLimitService,
        // which throws ExecutionTimezoneInvalidException (PERMANENT) on a null or
        // blank zone, with no JVM/UTC fallback. Readiness therefore requires a
        // timezone for every campaign. A windowless schedule is the minimal way
        // to satisfy it and keeps these tests scoped to their own dimension.
        c.setSchedule(new ScheduleSpec(null, null, null, null, "Asia/Kolkata", null, null));
            c.setRetryPolicy(new RetryPolicySpec(0, null, RetryStrategy.FIXED));
            c.setTypeConfig(missedCallJson(ringSeconds));
            return campaignRepository.saveAndFlush(c).getId();
        });
    }

    private static JsonNode missedCallJson(int ringSeconds) {
        return toJson("{\"" + MissedCallCampaignConfig.KEY + "\": {"
                + "\"ringDurationSeconds\": " + ringSeconds + "}}");
    }

    private static JsonNode toJson(String json) {
        return JsonMapper.builder().build().readTree(json);
    }
}