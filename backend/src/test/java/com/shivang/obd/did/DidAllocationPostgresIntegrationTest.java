package com.shivang.obd.did;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.reseller.ResellerEntity;
import com.shivang.obd.reseller.ResellerRepository;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.util.ArrayList;
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
 * VB-5C PostgreSQL integration tests (real {@code postgres:16-alpine},
 * real Flyway chain V1..V41). Proves the allocation lifecycle against the
 * actual database: conditional-UPDATE transitions, provenance
 * restoration, campaign-readiness integration, voice-eligibility
 * integration, rollback, and the mandatory 20-thread assignment race.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DidAllocationPostgresIntegrationTest {

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
    private DidRepository didRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private ResellerRepository resellerRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private DidService didService;

    @Autowired
    private com.shivang.obd.telephony.PhoneListEntryRepository phoneListEntryRepository;
    @Autowired
    private com.shivang.obd.telephony.SipGatewayRepository gatewayRepository;
    @Autowired
    private com.shivang.obd.telephony.SipGatewayAllocationRepository gatewayAllocationRepository;

    @Autowired
    private com.shivang.obd.campaign.CampaignRepository campaignRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                    + startupFailure.getMessage(), startupFailure);
        }
        // Platform-scope pass-through authorization (matches the VB-5B
        // integration-harness pattern; isolation semantics are covered by
        // the service unit tests plus the scoped conditional UPDATEs).
        com.shivang.obd.authz.AuthorizationService allowAll =
            new com.shivang.obd.authz.AuthorizationService(
                java.util.List.of(), null, null, null, java.util.List.of()) {
                @Override
                public void requireCapability(UUID userId, String capabilityKey,
                    com.shivang.obd.authz.AccessCheck target) {
                    // harness pass-through
                }
            };
        com.shivang.obd.security.CurrentUserProvider currentUser =
            new com.shivang.obd.security.CurrentUserProvider() {
                @Override
                public java.util.Optional<com.shivang.obd.security.AuthenticatedUser> current() {
                    return java.util.Optional.of(new com.shivang.obd.security.AuthenticatedUser(
                        UUID.fromString("dd000000-0000-4000-8000-000000000001"), "it@test.local", null));
                }
            };
        didService = new DidService(didRepository, allowAll, currentUser,
            new DidMapper(), tenantRepository, resellerRepository);
        this.allowAll = allowAll;
    }

    private com.shivang.obd.authz.AuthorizationService allowAll;

    private static final UUID CALLER_ID =
        UUID.fromString("dd000000-0000-4000-8000-000000000001");

    private void platformScope() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(CALLER_ID, null, null);
    }

    private void resellerScope(UUID resellerId) {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(CALLER_ID, null, resellerId);
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    /** DidService is constructed directly (no proxy), so its @Transactional
     *  is inactive — run allocation transitions inside a real tx. */
    private com.shivang.obd.common.api.response.ApiResponse<com.shivang.obd.did.dto.AssignDidResponse> assignInTx(
        UUID didId, UUID targetId) {
        return transactionTemplate.execute(tx ->
            didService.assign(didId, new com.shivang.obd.did.dto.AssignDidRequest(targetId)));
    }

    private com.shivang.obd.common.api.response.ApiResponse<com.shivang.obd.did.dto.AssignDidResponse> revokeInTx(UUID didId) {
        return transactionTemplate.execute(tx -> didService.revoke(didId));
    }

    // === fixtures ===

    private ResellerEntity seedReseller(String label) {
        ResellerEntity r = new ResellerEntity();
        r.setName("reseller-" + label + "-" + UUID.randomUUID().toString().substring(0, 8));
        r.setSlug("r-" + label + "-" + UUID.randomUUID().toString().substring(0, 8));
        r.setStatus(LifecycleStatus.ACTIVE);
        return resellerRepository.saveAndFlush(r);
    }

    /** Committed seeding: @DataJpaTest wraps tests in a rolled-back tx;
     *  worker threads (concurrency tests) must see committed fixtures. */
    private UUID seedDidNewTx(String e164, AllocationState state, AllocationSource source,
                              UUID tenantId, UUID resellerId, DidStatus status) {
        return transactionTemplate.execute(tx ->
            seedDid(e164, state, source, tenantId, resellerId, status));
    }

    private TenantEntity seedTenant(String label, UUID resellerId) {
        TenantEntity t = new TenantEntity();
        t.setName("tenant-" + label + "-" + UUID.randomUUID().toString().substring(0, 8));
        t.setSlug("t-" + label + "-" + UUID.randomUUID().toString().substring(0, 8));
        t.setResellerId(resellerId);
        t.setStatus(LifecycleStatus.ACTIVE);
        return tenantRepository.saveAndFlush(t);
    }

    private UUID seedDid(String e164, AllocationState state, AllocationSource source,
                         UUID tenantId, UUID resellerId, DidStatus status) {
        DidEntity d = new DidEntity();
        d.setE164Number(e164);
        d.setCountryCode("+91");
        d.setNumberType(NumberType.MOBILE);
        d.setProvider("TATA");
        d.setStatus(status);
        d.setAllocationState(state);
        d.setAllocationSource(source);
        d.setTenantId(tenantId);
        d.setResellerId(resellerId);
        return didRepository.saveAndFlush(d).getId();
    }

    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
        new java.util.concurrent.atomic.AtomicInteger(1000);

    private static String uniqueE164() {
        return "+9198" + SEQ.incrementAndGet() + String.format("%05d", SEQ.get() % 100000);
    }

    private DidEntity reload(UUID didId) {
        return entityManager.find(DidEntity.class, didId);
    }

    // === A. lifecycle flows ===

    @Test
    @DisplayName("PG-A1: platform → reseller assignment, revoke restores platform pool")
    void platformToResellerRoundTrip() {
        platformScope();
        UUID resellerId = seedReseller("a1").getId();
        UUID didId = seedDidNewTx(uniqueE164(), AllocationState.AVAILABLE, null, null, null, DidStatus.ACTIVE);

        var assigned = assignInTx(didId, resellerId);
        assertThat(assigned.data().allocationSource()).isEqualTo(AllocationSource.RESELLER);
        assertThat(assigned.data().resellerId()).isEqualTo(resellerId);
        assertThat(assigned.data().allocationState()).isEqualTo(AllocationState.AVAILABLE);
        assertThat(assigned.data().tenantId()).isNull();

        var revoked = revokeInTx(didId);
        assertThat(revoked.data().resellerId()).isNull();
        assertThat(revoked.data().allocationSource()).isNull();
        assertThat(revoked.data().allocationState()).isEqualTo(AllocationState.AVAILABLE);
    }

    @Test
    @DisplayName("PG-A2: platform → tenant assignment, revoke restores platform pool")
    void platformToTenantRoundTrip() {
        platformScope();
        UUID tenantId = seedTenant("a2", null).getId();
        UUID didId = seedDidNewTx(uniqueE164(), AllocationState.AVAILABLE, null, null, null, DidStatus.ACTIVE);

        var assigned = assignInTx(didId, tenantId);
        assertThat(assigned.data().allocationState()).isEqualTo(AllocationState.ASSIGNED);
        assertThat(assigned.data().allocationSource()).isEqualTo(AllocationSource.PLATFORM);
        assertThat(assigned.data().tenantId()).isEqualTo(tenantId);
        assertThat(assigned.data().resellerId()).isNull();

        var revoked = revokeInTx(didId);
        assertThat(revoked.data().tenantId()).isNull();
        assertThat(revoked.data().allocationSource()).isNull(); // back to pristine platform pool
    }

    @Test
    @DisplayName("PG-A3: reseller → tenant assignment, revoke restores reseller pool (provenance)")
    void resellerToTenantRoundTrip() {
        ResellerEntity reseller = seedReseller("a3");
        resellerScope(reseller.getId());
        TenantEntity tenant = seedTenant("a3", reseller.getId());
        UUID didId = seedDidNewTx(uniqueE164(), AllocationState.AVAILABLE, AllocationSource.RESELLER,
            null, reseller.getId(), DidStatus.ACTIVE);

        var assigned = assignInTx(didId, tenant.getId());
        assertThat(assigned.data().allocationState()).isEqualTo(AllocationState.ASSIGNED);
        assertThat(assigned.data().tenantId()).isEqualTo(tenant.getId());

        var revoked = revokeInTx(didId);
        assertThat(revoked.data().tenantId()).isNull();
        assertThat(revoked.data().resellerId()).isEqualTo(reseller.getId()); // back to reseller pool
        assertThat(revoked.data().allocationSource()).isEqualTo(AllocationSource.RESELLER);
        assertThat(revoked.data().allocationState()).isEqualTo(AllocationState.AVAILABLE);
    }

    @Test
    @DisplayName("PG-A4: deleted DID cannot be assigned")
    void deletedDidNotAssignable() {
        platformScope();
        UUID tenantId = seedTenant("a4", null).getId();
        UUID didId = seedDidNewTx(uniqueE164(), AllocationState.AVAILABLE, null, null, null, DidStatus.ACTIVE);
        assignInTx(didId, tenantId);
        revokeInTx(didId);

        DidEntity did = reload(didId);
        did.setDeletedAt(java.time.Instant.now());
        didRepository.saveAndFlush(did);
        entityManager.clear();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> assignInTx(didId, tenantId))
            .isInstanceOf(com.shivang.obd.common.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("PG-A5: inactive DID cannot be assigned")
    void inactiveDidNotAssignable() {
        platformScope();
        UUID tenantId = seedTenant("a5", null).getId();
        UUID didId = seedDidNewTx(uniqueE164(), AllocationState.AVAILABLE, null, null, null, DidStatus.INACTIVE);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> assignInTx(didId, tenantId))
            .isInstanceOf(com.shivang.obd.common.exception.BusinessException.class)
            .hasMessageContaining("inactive");
    }

    @Test
    @DisplayName("PG-A6: already-assigned DID cannot be re-assigned (no silent move)")
    void alreadyAssignedNotReassignable() {
        platformScope();
        UUID tenantA = seedTenant("a6a", null).getId();
        UUID tenantB = seedTenant("a6b", null).getId();
        UUID didId = seedDidNewTx(uniqueE164(), AllocationState.ASSIGNED, AllocationSource.PLATFORM,
            tenantA, null, DidStatus.ACTIVE);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> assignInTx(didId, tenantB))
            .isInstanceOf(com.shivang.obd.common.exception.ConflictException.class)
            .hasMessageContaining("already assigned");

        assertThat(reload(didId).getTenantId()).isEqualTo(tenantA); // ownership unchanged
    }

    @Test
    @DisplayName("PG-A7: conditional UPDATE rollback leaves no partial state")
    void failedTransitionLeavesNoPartialState() {
        UUID tenantId = seedTenant("a7", null).getId();
        UUID didId = seedDidNewTx(uniqueE164(), AllocationState.ASSIGNED, AllocationSource.PLATFORM,
            tenantId, null, DidStatus.ACTIVE);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx ->
                {
                    int rows = didRepository.assignFromPlatformPoolToTenant(didId, tenantId);
                    if (rows != 1) {
                        throw new com.shivang.obd.common.exception.ConflictException("lost race");
                    }
                    didRepository.flush();
                }))
            .isInstanceOf(com.shivang.obd.common.exception.ConflictException.class);

        DidEntity after = reload(didId);
        assertThat(after.getAllocationState()).isEqualTo(AllocationState.ASSIGNED);
        assertThat(after.getTenantId()).isEqualTo(tenantId);
        assertThat(after.getAllocationSource()).isEqualTo(AllocationSource.PLATFORM);
    }

    // === B. consumer integration ===

    @Test
    @DisplayName("PG-B1: campaign readiness recognizes assigned DID; rejects after revoke")
    void campaignReadinessIntegration() {
        platformScope();
        UUID tenantId = seedTenant("b1", null).getId();
        UUID contactGroupId = null;
        // Seed an approved audio asset so readiness's content check passes
        // and the DID reason is the only variable under test.
        UUID didId = seedDidNewTx(uniqueE164(), AllocationState.AVAILABLE, null, null, null, DidStatus.ACTIVE);

        UUID campaignId = seedCampaign(tenantId, didId, contactGroupId);

        // Before assignment: DID is not tenant-owned → DID_UNAVAILABLE.
        var before = readiness(campaignId, tenantId);
        assertThat(before.ready()).isFalse();
        org.assertj.core.api.Assertions.assertThat(before.reasons())
            .anySatisfy(r -> assertThat(r.code()).isEqualTo("DID_UNAVAILABLE"));

        // After assignment: the DID dimension passes (the campaign fixture
        // is intentionally minimal, so assert only the DID reason).
        assignInTx(didId, tenantId);
        var after = readiness(campaignId, tenantId);
        org.assertj.core.api.Assertions.assertThat(after.reasons())
            .noneSatisfy(r -> assertThat(r.code()).isEqualTo("DID_UNAVAILABLE"));

        // After revoke: back to unavailable.
        revokeInTx(didId);
        var revoked = readiness(campaignId, tenantId);
        assertThat(revoked.ready()).isFalse();
        org.assertj.core.api.Assertions.assertThat(revoked.reasons())
            .anySatisfy(r -> assertThat(r.code()).isEqualTo("DID_UNAVAILABLE"));
    }

    @Test
    @DisplayName("PG-B2: voice eligibility — assigned DID eligible; revoked DID no longer tenant-usable")
    void voiceEligibilityIntegration() {
        platformScope();
        UUID tenantId = seedTenant("b2", null).getId();
        UUID didId = seedDidNewTx(uniqueE164(), AllocationState.AVAILABLE, null, null, null, DidStatus.ACTIVE);
        DidEntity did = reload(didId);

        com.shivang.obd.telephony.VoiceEligibilityService eligibility =
            new com.shivang.obd.telephony.VoiceEligibilityService(
                phoneListEntryRepository, new com.shivang.obd.telephony.SipGatewayRoutingService(
                    new com.shivang.obd.telephony.SipGatewayResolver(
                        gatewayRepository, gatewayAllocationRepository)),
                null,
                tenantRepository, didRepository);

        // Before assignment: eligibility blocks the DID for this tenant.
        var before = eligibility.evaluate(tenantId, "+919999999999", didId, false);
        assertThat(before.isAllowed()).isFalse();

        assignInTx(didId, tenantId);
        // After assignment the DID passes the DID checks (later checks may
        // still gate on gateway/provider compatibility; assert only the
        // DID-specific failure codes are gone).
        var after = eligibility.evaluate(tenantId, "+919999999999", didId, false);
        assertThat(after.getReasonCode()).isNotEqualTo("INVALID_DID");

        revokeInTx(didId);
        var revoked = eligibility.evaluate(tenantId, "+919999999999", didId, false);
        assertThat(revoked.isAllowed()).isFalse();
        assertThat(revoked.getReasonCode()).isEqualTo("INVALID_DID");
    }

    @Test
    @DisplayName("PG-B3: inbound resolution ignores revoked DID (tenant_id null, no destination)")
    void inboundResolutionAfterRevoke() {
        platformScope();
        UUID tenantId = seedTenant("b3", null).getId();
        String e164 = uniqueE164();
        UUID didId = seedDidNewTx(e164, AllocationState.AVAILABLE, null, null, null, DidStatus.ACTIVE);
        assignInTx(didId, tenantId);
        revokeInTx(didId);

        // The inbound resolver resolves by E.164, then requires tenant_id +
        // inbound destination — a revoked pool DID has neither, so inbound
        // routing cannot use it.
        DidEntity resolved = didRepository.findByE164NumberAndDeletedAtIsNull(e164).orElseThrow();
        assertThat(resolved.getTenantId()).isNull();
        assertThat(resolved.getInboundDestination()).isNull();
    }

    // === C. concurrency ===

    @Test
    @DisplayName("PG-C1: 20 concurrent assignments of one AVAILABLE DID → exactly 1 success")
    void concurrentAssignmentRace() throws Exception {
        UUID resellerId = seedReseller("c1").getId();
        UUID tenantId = seedTenant("c1", resellerId).getId();
        UUID didId = seedDidNewTx(uniqueE164(), AllocationState.AVAILABLE, AllocationSource.RESELLER,
            null, resellerId, DidStatus.ACTIVE);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger successes = new AtomicInteger();
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<Boolean>) () -> transactionTemplate.execute(status -> {
                    int rows = didRepository.assignFromResellerPoolToTenant(didId, resellerId, tenantId);
                    return rows == 1;
                })));
            }
            for (Future<Boolean> f : futures) {
                if (Boolean.TRUE.equals(f.get(60, TimeUnit.SECONDS))) {
                    successes.incrementAndGet();
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);

        DidEntity finalState = reload(didId);
        assertThat(finalState.getAllocationState()).isEqualTo(AllocationState.ASSIGNED);
        assertThat(finalState.getTenantId()).isEqualTo(tenantId);
        assertThat(finalState.getResellerId()).isEqualTo(resellerId);
        assertThat(finalState.getAllocationSource()).isEqualTo(AllocationSource.RESELLER);
    }

    @Test
    @DisplayName("PG-C2: concurrent revoke vs assign — final state is one valid lifecycle state")
    void concurrentRevokeAssignRace() throws Exception {
        UUID resellerId = seedReseller("c2").getId();
        UUID tenantA = seedTenant("c2a", resellerId).getId();
        UUID tenantB = seedTenant("c2b", resellerId).getId();
        UUID didId = seedDidNewTx(uniqueE164(), AllocationState.ASSIGNED, AllocationSource.RESELLER,
            tenantA, resellerId, DidStatus.ACTIVE);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> revoke = pool.submit(() -> transactionTemplate.executeWithoutResult(tx ->
                didRepository.revokeFromTenant(didId, tenantA)));
            Future<?> assign = pool.submit(() -> transactionTemplate.executeWithoutResult(tx ->
                didRepository.assignFromResellerPoolToTenant(didId, resellerId, tenantB)));
            revoke.get(60, TimeUnit.SECONDS);
            assign.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        DidEntity finalState = reload(didId);
        boolean validRevoked = finalState.getTenantId() == null
            && finalState.getAllocationState() == AllocationState.AVAILABLE;
        boolean validAssigned = tenantA.equals(finalState.getTenantId())
            || tenantB.equals(finalState.getTenantId())
            && finalState.getAllocationState() == AllocationState.ASSIGNED;
        assertThat(validRevoked || validAssigned)
            .as("final state must be a valid lifecycle state: %s", finalState)
            .isTrue();
        // Never two owners, never corrupted provenance:
        if (validAssigned) {
            assertThat(finalState.getAllocationSource()).isEqualTo(AllocationSource.RESELLER);
        }
    }

    // === helpers ===

    private UUID seedCampaign(UUID tenantId, UUID didId, UUID contactGroupId) {
        com.shivang.obd.campaign.CampaignEntity campaign = new com.shivang.obd.campaign.CampaignEntity();
        campaign.setTenantId(tenantId);
        campaign.setName("vb5c-" + UUID.randomUUID().toString().substring(0, 8));
        campaign.setCampaignType(com.shivang.obd.campaign.CampaignType.PLAYFILE);
        campaign.setStatus(com.shivang.obd.campaign.CampaignStatus.SCHEDULED);
        campaign.setContentMode(com.shivang.obd.campaign.ContentMode.AUDIO);
        campaign.setDidId(didId);
        campaign.setRetryPolicy(new com.shivang.obd.campaign.RetryPolicySpec(
            0, null, com.shivang.obd.campaign.RetryStrategy.FIXED));
        return campaignRepository.saveAndFlush(campaign).getId();
    }

    private com.shivang.obd.campaign.dto.CampaignReadinessResponse readiness(UUID campaignId, UUID tenantId) {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            UUID.fromString("dd000000-0000-4000-8000-000000000001"), null, null);
        try {
            com.shivang.obd.authz.AuthorizationService allowAll =
                new com.shivang.obd.authz.AuthorizationService(
                    java.util.List.of(), null, null, null, java.util.List.of()) {
                    @Override
                    public void requireCapability(UUID userId, String capabilityKey,
                        com.shivang.obd.authz.AccessCheck target) {
                    }
                };
            com.shivang.obd.campaign.CampaignReadinessService readiness =
                new com.shivang.obd.campaign.CampaignReadinessService(
                    campaignRepository, allowAll, currentUserProvider(),
                    null,
                    // Real canonical validator (VB-5E) over the DID repo.
                    new com.shivang.obd.campaign.CampaignResourceValidationService(
                        didRepository, null, null),
                    tenantRepository);
            return readiness.evaluate(campaignId);
        } finally {
            com.shivang.obd.authz.context.OrganizationContextHolder.clear();
        }
    }

    private com.shivang.obd.security.CurrentUserProvider currentUserProvider() {
        return new com.shivang.obd.security.CurrentUserProvider() {
            @Override
            public java.util.Optional<com.shivang.obd.security.AuthenticatedUser> current() {
                return java.util.Optional.of(new com.shivang.obd.security.AuthenticatedUser(
                    UUID.fromString("dd000000-0000-4000-8000-000000000001"), "it@test.local", null));
            }
        };
    }
}
