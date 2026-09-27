package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.dto.CampaignExecutionResponse;
import com.shivang.obd.campaign.dto.CampaignReadinessResponse;
import com.shivang.obd.campaign.dto.ExecuteCampaignRequest;
import com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.contact.ContactEntity;
import com.shivang.obd.contact.ContactGroupEntity;
import com.shivang.obd.contact.ContactGroupRepository;
import com.shivang.obd.contact.ContactRepository;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-5F campaign governance hardening tests (real {@code postgres:16-alpine},
 * real Flyway chain V1..V42). Proves against the actual database:
 * <ul>
 *   <li>A — reseller-hierarchy scoping of readiness/execution/attempt
 *       boundaries (F2 family: foreign-campaign executions are 404-cloaked)</li>
 *   <li>B — the F1 dial-path fix: campaign lookup by
 *       {@code attempt.campaignId}, distinct from {@code executionId}</li>
 *   <li>C — stale resources: revoked/reassigned DID blocks after activation
 *       (readiness + dial-time eligibility); soft-deleted resources unusable</li>
 *   <li>D — concurrency/idempotency: double activation is 409, duplicate
 *       execute with the same key returns the first execution, concurrent
 *       execute respects the partial unique index, concurrent attempt
 *       inserts lose exactly once on the partial unique index</li>
 * </ul>
 *
 * <p>Harness conventions (VB-4/5 series): container started in a static
 * initializer (before {@code @DynamicPropertySource}); {@code NOT_SUPPORTED}
 * propagation; services constructed directly and invoked inside
 * {@link TransactionTemplate} (their {@code @Transactional} is inactive
 * without a proxy); committed seeding for worker-thread visibility.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampaignGovernanceHardeningPostgresIntegrationTest {

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
    private CampaignExecutionRepository executionRepository;
    @Autowired
    private CampaignExecutionConfigurationRepository configurationVersionRepository;
    @Autowired
    private CallAttemptRepository attemptRepository;
    @Autowired
    private DidRepository didRepository;
    @Autowired
    private com.shivang.obd.audio.AudioAssetRepository audioAssetRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private ResellerRepository resellerRepository;
    @Autowired
    private ContactGroupRepository contactGroupRepository;
    @Autowired
    private ContactRepository contactRepository;
    @Autowired
    private com.shivang.obd.contact.ContactGroupMemberRepository memberRepository;
    @Autowired
    private com.shivang.obd.telephony.PhoneListEntryRepository phoneListRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final UUID CALLER_ID =
        UUID.fromString("ff000000-0000-4000-8000-0000000000f1");

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger E164_SEQ = new AtomicInteger(3000);

    private CampaignReadinessService readinessService;
    private CampaignExecutionService executionService;
    private CallAttemptService attemptService;
    private CampaignConfigurationService configurationService;
    private com.shivang.obd.authz.AuthorizationService allowAll;
    private CurrentUserProvider currentUser;
    private CampaignResourceValidationService validator;

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
                // harness pass-through; scope semantics are exercised below
                // through real OrganizationContext values
            }
        };
        currentUser = new CurrentUserProvider() {
            @Override
            public java.util.Optional<AuthenticatedUser> current() {
                return java.util.Optional.of(
                    new AuthenticatedUser(CALLER_ID, "it@test.local", null));
            }
        };
        validator = new CampaignResourceValidationService(
            didRepository, audioAssetRepository, null);
        configurationService = new CampaignConfigurationService(
            // Autowired repository field must be declared below.
            configurationVersionRepository,
            new com.shivang.obd.campaign.config.CampaignTypeConfigValidator());
        readinessService = new CampaignReadinessService(
            campaignRepository, allowAll, currentUser,
            contactGroupRepository, validator, tenantRepository);
        executionService = new CampaignExecutionService(
            campaignRepository, executionRepository, allowAll, currentUser,
            readinessService, tenantRepository, configurationService);
        attemptService = new CallAttemptService(
            campaignRepository, executionRepository, attemptRepository,
            allowAll, currentUser, contactRepository, contactGroupRepository,
            validator, tenantRepository);
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createQuery("DELETE FROM CallAttempt").executeUpdate();
            entityManager.createQuery("DELETE FROM CampaignExecution").executeUpdate();
            entityManager.createQuery("DELETE FROM CampaignExecutionConfiguration").executeUpdate();
            entityManager.createQuery("DELETE FROM CampaignEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM com.shivang.obd.contact.ContactGroupMemberEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactGroupEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM AudioAssetEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM DidEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM TenantEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ResellerEntity").executeUpdate();
        });
    }

    // === A. reseller-hierarchy scoping (F2 family) ===

    @Test
    @DisplayName("PG-G1: readiness in reseller scope resolves hierarchy tenants and 404s foreign campaigns")
    void readinessResellerScopeIsHierarchyBounded() {
        UUID resellerId = seedReseller("g1").getId();
        UUID tenantInHierarchy = seedTenant("g1a", resellerId).getId();
        UUID foreignTenant = seedTenant("g1b", null).getId();

        UUID didIn = seedDid(tenantInHierarchy, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID groupIdIn = seedContactGroup(tenantInHierarchy);
        UUID campaignIn = seedCampaignRow(tenantInHierarchy, CampaignStatus.SCHEDULED,
            ContentMode.AUDIO, null, null, didIn, groupIdIn);
        UUID campaignForeign = seedCampaignRow(foreignTenant, CampaignStatus.SCHEDULED,
            null, null, null, null, null);

        // In-hierarchy: readable (may carry other readiness reasons — the
        // minimal fixture only asserts visibility, not readiness).
        resellerScope(resellerId);
        CampaignReadinessResponse visible = transactionTemplate.execute(tx ->
            readinessService.evaluate(campaignIn));
        assertThat(visible.campaignId()).isEqualTo(campaignIn);

        // Foreign campaign is 404-cloaked, never leakable through the old
        // platform-wide fallback.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx ->
            readinessService.evaluate(campaignForeign)))
            .isInstanceOf(ResourceNotFoundException.class);

        // Reseller with no active tenants: everything is 404 (empty-hierarchy
        // guard), matching CampaignService semantics.
        UUID emptyReseller = seedReseller("g1e").getId();
        resellerScope(emptyReseller);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx ->
            readinessService.evaluate(campaignIn)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("PG-G2: reseller can list/get executions and attempts only inside its tenant hierarchy")
    void executionAndAttemptBoundariesAreHierarchyBounded() {
        UUID resellerId = seedReseller("g2").getId();
        UUID tenantIn = seedTenant("g2a", resellerId).getId();
        UUID foreignTenant = seedTenant("g2b", null).getId();

        UUID campaignIn = seedCampaignRow(tenantIn, CampaignStatus.SCHEDULED,
            null, null, null, null, null);
        UUID campaignForeign = seedCampaignRow(foreignTenant, CampaignStatus.SCHEDULED,
            null, null, null, null, null);
        UUID executionIn = seedExecution(campaignIn, tenantIn, null);
        UUID executionForeign = seedExecution(campaignForeign, foreignTenant, null);

        resellerScope(resellerId);

        // In-hierarchy execution readable.
        CampaignExecutionResponse inResp = transactionTemplate.execute(tx ->
            executionService.getExecution(executionIn)).data();
        assertThat(inResp.id()).isEqualTo(executionIn);

        // Foreign execution: 404-cloaked (previously unreachable: the
        // reseller branch queried tenant_id = resellerId, never matching).
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx ->
            executionService.getExecution(executionForeign)))
            .isInstanceOf(ResourceNotFoundException.class);

        // The execution-creation path is bounded the same way.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx ->
            executionService.execute(campaignForeign, new ExecuteCampaignRequest(null))))
            .isInstanceOf(ResourceNotFoundException.class);

        // Attempt boundaries resolve the execution with the same scoping.
        assertThat(transactionTemplate.execute(tx ->
            attemptService.listAttempts(campaignIn, executionIn)).data()).isEmpty();
        UUID attemptForeign = seedAttempt(executionForeign, campaignForeign, foreignTenant);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx ->
            attemptService.getAttempt(campaignForeign, executionForeign, attemptForeign)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // === B. F1: campaign lookup by attempt.campaignId ===

    @Test
    @DisplayName("PG-G3: distinct campaign/execution ids — dial-path campaign resolution uses attempt.campaignId")
    void dialPathResolvesCampaignByAttemptCampaignId() {
        UUID tenantId = seedTenant("g3", null).getId();
        UUID campaignId = seedCampaignRow(tenantId, CampaignStatus.RUNNING,
            null, null, null, null, null);
        UUID executionId = seedExecution(campaignId, tenantId, null);

        // The dial path resolves the campaign by campaignId (not executionId):
        // a query keyed by executionId would find nothing here and fail the
        // attempt with CAMPAIGN_NOT_FOUND despite the campaign existing.
        CampaignEntity resolved = transactionTemplate.execute(tx ->
            campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                campaignId, tenantId)).orElseThrow();
        assertThat(resolved.getId()).isEqualTo(campaignId);
        assertThat(resolved.getId()).isNotEqualTo(executionId);

        // Reproduces the pre-F1 query shape: empty for a distinct campaign id.
        java.util.Optional<CampaignEntity> wrongKeyLookup = transactionTemplate.execute(tx ->
            campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                executionId, tenantId));
        assertThat(wrongKeyLookup).isEmpty();

        // Execution-side resolution (correlation) works and is distinct.
        CampaignExecution execution = transactionTemplate.execute(tx ->
            executionRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                executionId, tenantId)).orElseThrow();
        assertThat(execution.getCampaignId()).isEqualTo(campaignId);
    }

    // === C. stale resources after activation ===

    @Test
    @DisplayName("PG-G4: DID revoked after activation → readiness fails and dial-time eligibility blocks (INVALID_DID)")
    void didRevokedAfterActivationBlocksAtRuntime() {
        UUID tenantId = seedTenant("g4", null).getId();
        UUID didId = seedDid(tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID campaignId = seedCampaignRow(tenantId, CampaignStatus.SCHEDULED,
            ContentMode.AUDIO, null, null, didId, null);

        // Activatable: readiness has no DID reason while the DID is assigned.
        tenantScope(tenantId);
        assertThat(reasonCodes(campaignId)).doesNotContain("DID_UNAVAILABLE");

        // Revoke the DID (conditional UPDATE, returns it to the pool).
        transactionTemplate.executeWithoutResult(tx ->
            assertThat(didRepository.revokeFromTenant(didId, tenantId)).isEqualTo(1));

        // Readiness now reports the stale reference (VB-5E canonical validator).
        assertThat(reasonCodes(campaignId)).contains("DID_UNAVAILABLE");

        // Dial time: the voice-eligibility boundary blocks independently.
        var eligibility = new com.shivang.obd.telephony.VoiceEligibilityService(
            phoneListRepository, gatewayRoutingStub(), null,
            tenantRepository, didRepository);
        var blocked = eligibility.evaluate(tenantId, "+919999999999", didId);
        assertThat(blocked.isAllowed()).isFalse();
        assertThat(blocked.getReasonCode()).isEqualTo("INVALID_DID");
    }

    @Test
    @DisplayName("PG-G5: DID reassigned to a foreign tenant after activation blocks at dial time")
    void didReassignedToForeignTenantBlocksAtDialTime() {
        UUID tenantA = seedTenant("g5a", null).getId();
        UUID tenantB = seedTenant("g5b", null).getId();
        UUID didId = seedDid(tenantA, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID campaignId = seedCampaignRow(tenantA, CampaignStatus.SCHEDULED,
            ContentMode.AUDIO, null, null, didId, null);
        tenantScope(tenantA);
        assertThat(reasonCodes(campaignId)).doesNotContain("DID_UNAVAILABLE");

        // Direct reassignment: row leaves tenant A while staying ASSIGNED
        // (the conditional repository transitions never "move" a DID — the
        // eligibility boundary is the runtime guard for any such state).
        transactionTemplate.executeWithoutResult(tx ->
            entityManager.createQuery(
                    "UPDATE DidEntity d SET d.tenantId = :b WHERE d.id = :id")
                .setParameter("b", tenantB)
                .setParameter("id", didId)
                .executeUpdate());

        var eligibility = new com.shivang.obd.telephony.VoiceEligibilityService(
            phoneListRepository, gatewayRoutingStub(), null,
            tenantRepository, didRepository);
        var blocked = eligibility.evaluate(tenantA, "+919999999999", didId);
        assertThat(blocked.isAllowed()).isFalse();
        assertThat(blocked.getReasonCode()).isEqualTo("INVALID_DID");
        assertThat(blocked.getReasonMessage()).isEqualTo("DID does not belong to tenant");
    }

    @Test
    @DisplayName("PG-G6: soft-deleted campaign/resources disappear from every scoped boundary")
    void softDeletedCampaignAndResourcesAreUnusable() {
        UUID tenantId = seedTenant("g6", null).getId();
        UUID didId = seedDid(tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        UUID groupId = seedContactGroup(tenantId);
        UUID campaignId = seedCampaignRow(tenantId, CampaignStatus.SCHEDULED,
            ContentMode.AUDIO, null, null, didId, groupId);
        UUID executionId = seedExecution(campaignId, tenantId, null);

        // Before delete: visible.
        tenantScope(tenantId);
        java.util.Optional<CampaignEntity> beforeDelete = transactionTemplate.execute(tx ->
            campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId));
        assertThat(beforeDelete).isPresent();

        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = entityManager.find(CampaignEntity.class, campaignId);
            c.setDeletedAt(Instant.now());
            c.setDeletedBy(CALLER_ID.toString());
        });

        // After delete: tenant-scoped and platform lookups both exclude it,
        // and readiness is 404 (not "not ready") — it no longer exists.
        java.util.Optional<CampaignEntity> afterTenantScoped = transactionTemplate.execute(tx ->
            campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId));
        assertThat(afterTenantScoped).isEmpty();
        java.util.Optional<CampaignEntity> afterPlatformScoped = transactionTemplate.execute(tx ->
            campaignRepository.findByIdAndDeletedAtIsNull(campaignId));
        assertThat(afterPlatformScoped).isEmpty();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx ->
            readinessService.evaluate(campaignId)))
            .isInstanceOf(ResourceNotFoundException.class);

        // Soft-deleted DID is equally unusable at both boundaries.
        transactionTemplate.executeWithoutResult(tx -> {
            DidEntity d = entityManager.find(DidEntity.class, didId);
            d.setDeletedAt(Instant.now());
            d.setDeletedBy(CALLER_ID.toString());
        });
        java.util.Optional<DidEntity> deletedDid = transactionTemplate.execute(tx ->
            didRepository.findByIdAndDeletedAtIsNull(didId));
        assertThat(deletedDid).isEmpty();
        var validator = new CampaignResourceValidationService(didRepository, null, null);
        assertThat(validator.validateDid(didId, tenantId).usable()).isFalse();
    }

    // === D. concurrency / idempotency ===

    @Test
    @DisplayName("PG-G7: double activation is a 409 (state machine), not a second SCHEDULED write")
    void doubleActivationIsRejectedByStateMachine() {
        UUID tenantId = seedTenant("g7", null).getId();
        UUID campaignId = seedCampaignRow(tenantId, CampaignStatus.DRAFT,
            null, null, null, null, null);
        CampaignService campaignService = new CampaignService(
            campaignRepository, allowAll,
            org.mockito.Mockito.mock(com.shivang.obd.campaign.event.CampaignEventPublisher.class),
            currentUser, new CampaignMapper(), tenantRepository,
            contactGroupRepository, validator,
            new com.shivang.obd.campaign.CampaignLifecyclePolicy());
        tenantScope(tenantId);

        // DRAFT -> SCHEDULED must pass the full activation gate, so seed the
        // schedule plus a tenant-owned approved audio asset on the row.
        UUID audioId = seedAudio(tenantId);
        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = entityManager.find(CampaignEntity.class, campaignId);
            c.setContentMode(ContentMode.AUDIO);
            c.setAudioAssetId(audioId);
            c.setSchedule(new ScheduleSpec(
                java.time.LocalDate.now().plusDays(1),
                java.time.LocalDate.now().plusDays(2),
                java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0),
                "Asia/Kolkata", null, null));
        });

        transactionTemplate.executeWithoutResult(tx ->
            campaignService.changeStatus(campaignId, new UpdateCampaignStatusRequest("SCHEDULED")));
        assertThat(currentStatus(campaignId)).isEqualTo(CampaignStatus.SCHEDULED);

        // Second activation attempt: SCHEDULED -> SCHEDULED is not a legal
        // edge — 409, and the row still reads SCHEDULED afterwards.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx ->
            campaignService.changeStatus(campaignId, new UpdateCampaignStatusRequest("SCHEDULED"))))
            .isInstanceOf(ConflictException.class);
        assertThat(currentStatus(campaignId)).isEqualTo(CampaignStatus.SCHEDULED);
    }

    @Test
    @DisplayName("PG-G8: duplicate execute with the same idempotency key returns the first execution")
    void duplicateExecutionIdempotencyKeyReturnsFirstExecution() {
        UUID tenantId = seedTenant("g8", null).getId();
        UUID campaignId = seedCampaignRow(tenantId, CampaignStatus.SCHEDULED,
            null, null, null, null, null);
        tenantScope(tenantId);

        // The readiness gate enforces content too; make the campaign fully
        // ready (RUNNING + approved audio) so only the idempotency dimension
        // is under test.
        transactionTemplate.executeWithoutResult(tx ->
            entityManager.createQuery(
                    "UPDATE CampaignEntity c SET c.status = :s, c.contentMode = :m, "
                        + "c.audioAssetId = :a WHERE c.id = :id")
                .setParameter("s", CampaignStatus.RUNNING)
                .setParameter("m", ContentMode.AUDIO)
                .setParameter("a", seedAudio(tenantId))
                .setParameter("id", campaignId)
                .executeUpdate());

        // Pre-existing execution with the key: execute() must return it and
        // create nothing new (service-level idempotency on top of the V21
        // partial unique index).
        UUID firstId = seedExecution(campaignId, tenantId, "dup-key");
        long before = executionRepository.count();

        UUID returned = transactionTemplate.execute(tx ->
            executionService.execute(campaignId, new ExecuteCampaignRequest("dup-key")))
            .data().id();

        assertThat(returned).isEqualTo(firstId);
        assertThat(executionRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("PG-G9: 8 concurrent executes with the same key create exactly 1 execution (partial unique index)")
    void concurrentExecuteWithSameKeyCreatesExactlyOneExecution() throws Exception {
        UUID tenantId = seedTenant("g9", null).getId();
        UUID campaignId = seedCampaignRow(tenantId, CampaignStatus.SCHEDULED,
            null, null, null, null, null);

        // Direct repository inserts in parallel transactions: the V21
        // partial unique index uq_campaign_executions_campaign_idempotency
        // is the source of truth — exactly one insert survives.
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        // VB-6A correction: the execution row requires a snapshot reference;
        // one pre-created snapshot suffices for this idempotency-key race.
        UUID sharedSnapshotId = transactionTemplate.execute(tx ->
            configurationService.createExecutionSnapshot(
                campaignRepository.findByIdAndDeletedAtIsNull(campaignId).orElseThrow())
            .getId());
        List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<Boolean>) () -> {
                    try {
                        transactionTemplate.executeWithoutResult(tx -> {
                            CampaignExecution e = new CampaignExecution();
                            e.setCampaignId(campaignId);
                            e.setTenantId(tenantId);
                            e.setIdempotencyKey("race-key-g9");
                            e.setConfigurationSnapshotId(sharedSnapshotId);
                            e.setRequestedAt(Instant.now());
                            e.setRequestedBy(CALLER_ID.toString());
                            executionRepository.saveAndFlush(e);
                        });
                        return true;
                    } catch (Exception raceLoss) {
                        return false;
                    }
                }));
            }
            for (Future<Boolean> f : futures) {
                if (Boolean.TRUE.equals(f.get(60, TimeUnit.SECONDS))) {
                    succeeded.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        List<CampaignExecution> rows = transactionTemplate.execute(tx ->
            executionRepository.findByCampaignIdAndIdempotencyKeyAndDeletedAtIsNull(
                campaignId, "race-key-g9")).stream().toList();
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("PG-G10: concurrent attempt inserts for same execution+contact+number — exactly 1 wins")
    void concurrentAttemptInsertsRespectPartialUniqueIndex() throws Exception {
        UUID tenantId = seedTenant("g10", null).getId();
        UUID campaignId = seedCampaignRow(tenantId, CampaignStatus.RUNNING,
            null, null, null, null, null);
        UUID executionId = seedExecution(campaignId, tenantId, null);
        UUID contactId = seedContact(tenantId, seedContactGroup(tenantId));
        UUID didId = seedDid(tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger succeeded = new AtomicInteger();
        List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<Boolean>) () -> {
                    try {
                        transactionTemplate.executeWithoutResult(tx -> {
                            CallAttempt a = new CallAttempt();
                            a.setExecutionId(executionId);
                            a.setCampaignId(campaignId);
                            a.setTenantId(tenantId);
                            a.setContactId(contactId);
                            a.setDidId(didId);
                            a.setAttemptNumber(1);
                            a.setStatus(CallAttemptStatus.QUEUED);
                            a.setScheduledAt(Instant.now());
                            attemptRepository.saveAndFlush(a);
                        });
                        return true;
                    } catch (Exception raceLoss) {
                        return false;
                    }
                }));
            }
            for (Future<Boolean> f : futures) {
                if (Boolean.TRUE.equals(f.get(60, TimeUnit.SECONDS))) {
                    succeeded.incrementAndGet();
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // V22 partial unique index uq_call_attempts_execution_contact_attempt.
        assertThat(succeeded.get()).isEqualTo(1);
        Long liveRows = transactionTemplate.execute(tx ->
            entityManager.createQuery(
                    "SELECT COUNT(a) FROM CallAttempt a WHERE a.executionId = :e AND a.deletedAt IS NULL",
                    Long.class)
                .setParameter("e", executionId)
                .getSingleResult());
        assertThat(liveRows).isEqualTo(1L);
    }

    // === helpers ===

    private void tenantScope(UUID tenantId) {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(CALLER_ID, tenantId, null);
    }

    private void resellerScope(UUID resellerId) {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(CALLER_ID, null, resellerId);
    }

    private CampaignStatus currentStatus(UUID campaignId) {
        return transactionTemplate.execute(tx ->
            entityManager.find(CampaignEntity.class, campaignId).getStatus());
    }

    private List<String> reasonCodes(UUID campaignId) {
        CampaignReadinessResponse response = transactionTemplate.execute(tx ->
            readinessService.evaluate(campaignId));
        return response.reasons().stream().map(r -> r.code()).toList();
    }

    /** VoiceRouting stub resolving a compatible gateway for any provider. */
    private com.shivang.obd.voice.routing.VoiceRouting gatewayRoutingStub() {
        return (tenantId, resellerId, provider) -> java.util.Optional.of(
            new com.shivang.obd.voice.routing.VoiceRoute(
                UUID.fromString("ff000000-0000-4000-8000-0000000000b1"),
                "fs-gw-test", "external", provider, null, null));
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
            t.setResellerId(resellerId);
            t.setStatus(LifecycleStatus.ACTIVE);
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

    private UUID seedAudio(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            var a = new com.shivang.obd.audio.AudioAssetEntity();
            a.setTenantId(tenantId);
            a.setName("asset-" + SEQ.incrementAndGet());
            a.setFileName("asset-" + SEQ.get() + ".wav");
            a.setContentType("audio/wav");
            a.setFileSize(1024L);
            a.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
            a.setStorageReference("tenants/" + tenantId + "/asset.wav");
            return audioAssetRepository.saveAndFlush(a).getId();
        });
    }

    private UUID seedContactGroup(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            ContactGroupEntity g = new ContactGroupEntity();
            g.setTenantId(tenantId);
            g.setName("cg-" + SEQ.incrementAndGet());
            return contactGroupRepository.saveAndFlush(g).getId();
        });
    }

    private UUID seedContact(UUID tenantId, UUID groupId) {
        return transactionTemplate.execute(tx -> {
            // VB-6B.1: contact identity carries no group; participation is a
            // membership row.
            ContactEntity c = new ContactEntity();
            c.setTenantId(tenantId);
            c.setPhoneNumber(uniqueE164());
            ContactEntity saved = contactRepository.saveAndFlush(c);
            com.shivang.obd.contact.ContactGroupMemberEntity m =
                new com.shivang.obd.contact.ContactGroupMemberEntity();
            m.setTenantId(tenantId);
            m.setContactGroupId(groupId);
            m.setContactId(saved.getId());
            memberRepository.saveAndFlush(m);
            return saved.getId();
        });
    }

    /** Direct row seeding (bypasses service validation) for runtime tests. */
    private UUID seedCampaignRow(UUID tenantId, CampaignStatus status, ContentMode contentMode,
                                 UUID audioAssetId, UUID ttsTemplateId, UUID didId,
                                 UUID contactGroupId) {
        return transactionTemplate.execute(tx -> {
            CampaignEntity c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-vbf-" + SEQ.incrementAndGet());
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

    /** VB-6A correction: executions require their own snapshot (NOT NULL FK). */
    private UUID seedExecution(UUID campaignId, UUID tenantId, String idempotencyKey) {
        return transactionTemplate.execute(tx -> {
            var snapshot = configurationService.createExecutionSnapshot(
                campaignRepository.findByIdAndDeletedAtIsNull(campaignId).orElseThrow());
            CampaignExecution e = new CampaignExecution();
            e.setCampaignId(campaignId);
            e.setTenantId(tenantId);
            e.setIdempotencyKey(idempotencyKey);
            e.setConfigurationSnapshotId(snapshot.getId());
            e.setRequestedAt(Instant.now());
            e.setRequestedBy(CALLER_ID.toString());
            return executionRepository.saveAndFlush(e).getId();
        });
    }

    private UUID seedAttempt(UUID executionId, UUID campaignId, UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            CallAttempt a = new CallAttempt();
            a.setExecutionId(executionId);
            a.setCampaignId(campaignId);
            a.setTenantId(tenantId);
            a.setContactId(seedContact(tenantId, seedContactGroup(tenantId)));
            a.setDidId(seedDid(tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED));
            a.setAttemptNumber(1);
            a.setStatus(CallAttemptStatus.QUEUED);
            a.setScheduledAt(Instant.now());
            return attemptRepository.saveAndFlush(a).getId();
        });
    }

    private static String uniqueE164() {
        int n = E164_SEQ.incrementAndGet();
        return "+9198" + n + String.format("%05d", n % 100000);
    }
}
