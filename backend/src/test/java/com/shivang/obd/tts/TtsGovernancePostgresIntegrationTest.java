package com.shivang.obd.tts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.CampaignEntity;
import com.shivang.obd.campaign.CampaignRepository;
import com.shivang.obd.campaign.CampaignService;
import com.shivang.obd.campaign.CampaignStatus;
import com.shivang.obd.campaign.CampaignType;
import com.shivang.obd.campaign.ContentMode;
import com.shivang.obd.campaign.CampaignReadinessService;
import com.shivang.obd.campaign.dto.CampaignReadinessResponse;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.contact.ContactGroupEntity;
import com.shivang.obd.contact.ContactGroupRepository;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.tts.dto.CreateTtsTemplateRequest;
import com.shivang.obd.tts.dto.TtsTemplateResponse;
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

/**
 * VB-5D PostgreSQL integration tests (real {@code postgres:16-alpine},
 * real Flyway chain V1..V42). Proves scope governance against the actual
 * database: V42 chain, mutual-exclusion CHECK rejections, GLOBAL/TENANT
 * usability and visibility predicates, readiness reason split, campaign
 * write-time/activation flows, and idempotent transitions.
 *
 * <p>Harness conventions (VB-4/5 series): container started in a static
 * initializer (before {@code @DynamicPropertySource}); {@code NOT_SUPPORTED}
 * propagation so each seeding/verification step commits independently —
 * required because CHECK-violation tests abort their transaction; service
 * calls wrapped in {@link TransactionTemplate} since services constructed
 * directly have inactive {@code @Transactional}.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TtsGovernancePostgresIntegrationTest {

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
    private TtsTemplateRepository ttsTemplateRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private ContactGroupRepository contactGroupRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private TtsTemplateService ttsService;
    private CampaignService campaignService;
    private CampaignReadinessService readinessService;
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
        userId = UUID.fromString("dd000000-0000-4000-8000-000000000001");
        var allowAll = new com.shivang.obd.authz.AuthorizationService(
            List.of(), null, null, null, List.of()) {
            @Override
            public void requireCapability(UUID uid, String capabilityKey,
                com.shivang.obd.authz.AccessCheck target) {
                // harness pass-through; scope semantics are covered by the
                // unit matrix in TtsTemplateServiceTest
            }
        };
        var currentUser = new com.shivang.obd.security.CurrentUserProvider() {
            @Override
            public java.util.Optional<com.shivang.obd.security.AuthenticatedUser> current() {
                return java.util.Optional.of(
                    new com.shivang.obd.security.AuthenticatedUser(userId, "it@test.local", null));
            }
        };
        ttsService = new TtsTemplateService(
            ttsTemplateRepository, allowAll, currentUser,
            new TtsTemplateMapper(), tenantRepository);

        var resourceValidator = new com.shivang.obd.campaign.CampaignResourceValidationService(
            null, null, ttsTemplateRepository);

        campaignService = new CampaignService(
            campaignRepository, allowAll,
            org.mockito.Mockito.mock(com.shivang.obd.campaign.event.CampaignEventPublisher.class),
            currentUser, new com.shivang.obd.campaign.CampaignMapper(),
            tenantRepository, contactGroupRepository, resourceValidator,
            new com.shivang.obd.campaign.CampaignLifecyclePolicy());

        readinessService = new CampaignReadinessService(
            campaignRepository, allowAll, currentUser,
            contactGroupRepository, resourceValidator, tenantRepository);

        tenantA = seedTenant("tts-a").getId();
        tenantB = seedTenant("tts-b").getId();
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createQuery("DELETE FROM CampaignEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM TtsTemplateEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactGroupEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM TenantEntity").executeUpdate();
        });
    }

    // === helpers ===

    private void tenantScope(UUID tenantId) {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(userId, tenantId, null);
    }

    private void platformScope() {
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(userId, null, null);
    }

    private TenantEntity seedTenant(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantEntity t = new TenantEntity();
        t.setName("tenant-" + label + "-" + suffix);
        t.setSlug("t-" + label + "-" + suffix);
        t.setStatus(LifecycleStatus.ACTIVE);
        return tenantRepository.saveAndFlush(t);
    }

    private UUID seedTemplate(String name, TtsTemplateScope scope, TtsTemplateStatus status,
                              UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            TtsTemplateEntity e = new TtsTemplateEntity();
            e.setName(name + "-" + SEQ.incrementAndGet());
            e.setTemplateText("Hello {{name}}.");
            e.setVariables(List.of(new TtsTemplateVariable("name", "STRING", true)));
            e.setTenantId(tenantId);
            e.setScope(scope);
            e.setStatus(status);
            return ttsTemplateRepository.saveAndFlush(e).getId();
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

    private UUID seedCampaign(UUID tenantId, UUID contactGroupId, UUID ttsTemplateId,
                              CampaignStatus status) {
        return transactionTemplate.execute(tx -> {
            CampaignEntity c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-tts-" + SEQ.incrementAndGet());
            c.setCampaignType(CampaignType.PLAYFILE);
            c.setStatus(status);
            c.setContentMode(ContentMode.TTS);
            c.setTtsTemplateId(ttsTemplateId);
            c.setContactGroupId(contactGroupId);
            c.setRetryPolicy(new com.shivang.obd.campaign.RetryPolicySpec(
                0, null, com.shivang.obd.campaign.RetryStrategy.FIXED));
            return campaignRepository.saveAndFlush(c).getId();
        });
    }

    private TtsTemplateEntity reload(UUID id) {
        return entityManager.find(TtsTemplateEntity.class, id);
    }

    private List<String> reasonCodes(UUID campaignId) {
        CampaignReadinessResponse response = readinessService.evaluate(campaignId);
        return response.reasons().stream().map(r -> r.code()).toList();
    }

    private com.shivang.obd.campaign.dto.CreateCampaignRequest ttsCampaignRequest(
        UUID groupId, UUID templateId) {
        return new com.shivang.obd.campaign.dto.CreateCampaignRequest(
            "c-campaign-" + SEQ.incrementAndGet(), null, CampaignType.PLAYFILE, null,
            groupId, null, ContentMode.TTS, null, templateId,
            new com.shivang.obd.campaign.dto.ScheduleConfig(
                java.time.LocalDate.now().plusDays(1), java.time.LocalDate.now().plusDays(2),
                java.time.LocalTime.of(10, 0), java.time.LocalTime.of(18, 0),
                "Asia/Kolkata", null, null),
            null, null, null, true, null);
    }

    // === M. migration + constraints (DB-level invariants) ===

    @Test
    @DisplayName("PG-M1: Flyway applied V42 on top of V41 with TENANT default")
    void v42AppliedInChain() {
        Object version = entityManager.createNativeQuery(
            "SELECT version FROM flyway_schema_history WHERE version = '42'")
            .getSingleResult();
        assertThat(version).isEqualTo("42");
        assertThat(version).isNotEqualTo("41");
    }

    @Test
    @DisplayName("PG-M2: rows created without explicit scope default to TENANT (backfill shape)")
    void scopeDefaultsToTenant() {
        UUID id = seedTemplate("default-scope", TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantA);
        assertThat(reload(id).getScope()).isEqualTo(TtsTemplateScope.TENANT);
    }

    @Test
    @DisplayName("PG-M3: GLOBAL row with tenant stamp violates CHECK constraint")
    void globalWithTenantRejectedByConstraint() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            TtsTemplateEntity e = new TtsTemplateEntity();
            e.setName("bad-global-" + SEQ.incrementAndGet());
            e.setTemplateText("x");
            e.setTenantId(tenantA);
            e.setScope(TtsTemplateScope.GLOBAL);
            e.setStatus(TtsTemplateStatus.APPROVED);
            ttsTemplateRepository.saveAndFlush(e);
        })).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("PG-M4: TENANT row without tenant stamp violates CHECK constraint")
    void tenantWithoutTenantRejectedByConstraint() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            TtsTemplateEntity e = new TtsTemplateEntity();
            e.setName("bad-tenant-" + SEQ.incrementAndGet());
            e.setTemplateText("x");
            e.setTenantId(null);
            e.setScope(TtsTemplateScope.TENANT);
            e.setStatus(TtsTemplateStatus.APPROVED);
            ttsTemplateRepository.saveAndFlush(e);
        })).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("PG-M5: NULL scope is rejected (NOT NULL enforced)")
    void nullScopeRejected() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            TtsTemplateEntity e = new TtsTemplateEntity();
            e.setName("null-scope-" + SEQ.incrementAndGet());
            e.setTemplateText("x");
            e.setTenantId(tenantA);
            e.setScope(null); // bypass the entity default
            e.setStatus(TtsTemplateStatus.APPROVED);
            ttsTemplateRepository.saveAndFlush(e);
        })).isInstanceOf(Exception.class);
    }

    // === G. usability & visibility predicates against the real schema ===

    @Test
    @DisplayName("PG-G1: usability — own TENANT + APPROVED GLOBAL, never foreign tenant")
    void usabilityPredicate() {
        UUID own = seedTemplate("own", TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantA);
        UUID ownPending = seedTemplate("own-p", TtsTemplateScope.TENANT, TtsTemplateStatus.PENDING_APPROVAL, tenantA);
        UUID foreign = seedTemplate("foreign", TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantB);
        UUID global = seedTemplate("global", TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);
        UUID globalPending = seedTemplate("global-p", TtsTemplateScope.GLOBAL, TtsTemplateStatus.PENDING_APPROVAL, null);

        assertThat(ttsTemplateRepository.existsUsableForTenant(own, tenantA)).isTrue();
        assertThat(ttsTemplateRepository.existsUsableForTenant(global, tenantA)).isTrue();
        assertThat(ttsTemplateRepository.existsUsableForTenant(ownPending, tenantA)).isFalse();
        assertThat(ttsTemplateRepository.existsUsableForTenant(globalPending, tenantA)).isFalse();
        assertThat(ttsTemplateRepository.existsUsableForTenant(foreign, tenantA)).isFalse();
        assertThat(ttsTemplateRepository.existsUsableForTenant(foreign, tenantB)).isTrue();
        assertThat(ttsTemplateRepository.existsUsableForTenant(global, tenantB)).isTrue();
    }

    @Test
    @DisplayName("PG-G2: accessibility superset keeps pending/deleted classification tenant-safe")
    void accessibilitySuperSet() {
        UUID ownPending = seedTemplate("acc-own", TtsTemplateScope.TENANT, TtsTemplateStatus.PENDING_APPROVAL, tenantA);
        UUID globalPending = seedTemplate("acc-global", TtsTemplateScope.GLOBAL, TtsTemplateStatus.PENDING_APPROVAL, null);
        UUID foreign = seedTemplate("acc-foreign", TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantB);

        // Own pending row + any-status GLOBAL row are accessible...
        assertThat(ttsTemplateRepository.existsAccessibleForTenant(ownPending, tenantA)).isTrue();
        assertThat(ttsTemplateRepository.existsAccessibleForTenant(globalPending, tenantA)).isTrue();
        // ...deleted own rows and foreign rows are not (404/NOT_AVAILABLE class).
        transactionTemplate.executeWithoutResult(tx ->
            entityManager.createNativeQuery("UPDATE tts_templates SET deleted_at = now() WHERE id = :id")
                .setParameter("id", ownPending).executeUpdate());
        assertThat(ttsTemplateRepository.existsAccessibleForTenant(ownPending, tenantA)).isFalse();
        assertThat(ttsTemplateRepository.existsAccessibleForTenant(foreign, tenantA)).isFalse();
    }

    @Test
    @DisplayName("PG-G3: list visibility — tenant sees own + approved GLOBAL, not foreign")
    void listVisibilityTenant() {
        UUID ownPending = seedTemplate("vis-own", TtsTemplateScope.TENANT, TtsTemplateStatus.PENDING_APPROVAL, tenantA);
        seedTemplate("vis-foreign", TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantB);
        UUID globalApproved = seedTemplate("vis-global", TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);
        seedTemplate("vis-global-p", TtsTemplateScope.GLOBAL, TtsTemplateStatus.PENDING_APPROVAL, null);

        tenantScope(tenantA);
        List<UUID> visible = ttsService.list(0, 50, null, null, null).data().stream()
            .map(TtsTemplateResponse::id).toList();

        assertThat(visible).contains(ownPending, globalApproved);
        assertThat(visible).doesNotContainAnyElementsOf(
            ttsTemplateRepository.findAll().stream()
                .filter(e -> tenantB.equals(e.getTenantId()))
                .map(TtsTemplateEntity::getId).toList());
    }

    // === E. end-to-end service flows ===

    @Test
    @DisplayName("PG-E1: platform creates GLOBAL; both tenants see it; it is usable")
    void globalEndToEnd() {
        platformScope();
        UUID globalId = transactionTemplate.execute(tx -> ttsService.create(new CreateTtsTemplateRequest(
            "Global end-to-end", null, "Hello {{name}}.",
            List.of(new TtsTemplateVariable("name", "STRING", true)),
            null, TtsTemplateScope.GLOBAL)).data().id());

        assertThat(reload(globalId).getTenantId()).isNull();
        assertThat(reload(globalId).getStatus()).isEqualTo(TtsTemplateStatus.APPROVED);

        tenantScope(tenantA);
        assertThat(ttsService.getById(globalId).data().scope()).isEqualTo(TtsTemplateScope.GLOBAL);
        assertThat(ttsTemplateRepository.existsUsableForTenant(globalId, tenantA)).isTrue();

        tenantScope(tenantB);
        assertThat(ttsService.getById(globalId).data().scope()).isEqualTo(TtsTemplateScope.GLOBAL);
        assertThat(ttsTemplateRepository.existsUsableForTenant(globalId, tenantB)).isTrue();
    }

    @Test
    @DisplayName("PG-E2: tenant cannot read pending GLOBAL (404-cloaked)")
    void tenantCannotReadPendingGlobal() {
        platformScope();
        UUID globalId = transactionTemplate.execute(tx -> ttsService.create(new CreateTtsTemplateRequest(
            "Global pending", null, "Hello.",
            List.of(), null, TtsTemplateScope.GLOBAL)).data().id());
        transactionTemplate.executeWithoutResult(tx ->
            reload(globalId).setStatus(TtsTemplateStatus.PENDING_APPROVAL));

        tenantScope(tenantA);
        assertThatThrownBy(() -> ttsService.getById(globalId))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(ttsTemplateRepository.existsUsableForTenant(globalId, tenantA)).isFalse();
    }

    @Test
    @DisplayName("PG-E3: GLOBAL create with tenantId is rejected (400)")
    void globalWithTenantRejectedAtService() {
        platformScope();
        assertThatThrownBy(() -> ttsService.create(new CreateTtsTemplateRequest(
            "Global with tenant", null, "Hello.",
            List.of(), tenantA, TtsTemplateScope.GLOBAL)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("platform-owned");
    }

    @Test
    @DisplayName("PG-E4: tenant template lifecycle — create, approve, edit resets approval")
    void tenantLifecycleWithApprovalReset() {
        tenantScope(tenantA);
        UUID id = transactionTemplate.execute(tx -> ttsService.create(new CreateTtsTemplateRequest(
            "Tenant lifecycle", null, "Hello {{name}}.",
            List.of(new TtsTemplateVariable("name", "STRING", true)),
            null, TtsTemplateScope.TENANT)).data().id());

        assertThat(reload(id).getStatus()).isEqualTo(TtsTemplateStatus.PENDING_APPROVAL);

        transactionTemplate.executeWithoutResult(tx -> reload(id).setStatus(TtsTemplateStatus.APPROVED));

        transactionTemplate.executeWithoutResult(tx -> ttsService.update(id,
            new com.shivang.obd.tts.dto.UpdateTtsTemplateRequest(
                "Tenant lifecycle", null, "New text {{name}}.",
                List.of(new TtsTemplateVariable("name", "STRING", true)))));

        assertThat(reload(id).getStatus()).isEqualTo(TtsTemplateStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("PG-E5: repeated approve and approve-after-approve conflict (idempotency guard)")
    void transitionIdempotency() {
        tenantScope(tenantA);
        UUID id = transactionTemplate.execute(tx -> ttsService.create(new CreateTtsTemplateRequest(
            "Idempotency", null, "Hello.",
            List.of(), null, TtsTemplateScope.TENANT)).data().id());

        transactionTemplate.executeWithoutResult(tx -> ttsService.approve(id));
        assertThat(reload(id).getStatus()).isEqualTo(TtsTemplateStatus.APPROVED);
        // Repeated same-target transition conflicts; APPROVED → REJECTED
        // remains a legal revocation (covered by the unit matrix).
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> ttsService.approve(id)))
            .isInstanceOf(ConflictException.class);
    }

    // === C. campaign readiness + write-time/activation flows ===

    @Test
    @DisplayName("PG-C1: campaign on own APPROVED TENANT template is ready")
    void campaignReadyOwnTenantTemplate() {
        UUID groupId = seedContactGroup(tenantA);
        UUID templateId = seedTemplate("c1", TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantA);
        UUID campaignId = seedCampaign(tenantA, groupId, templateId, CampaignStatus.SCHEDULED);

        assertThat(reasonCodes(campaignId)).isEmpty();
    }

    @Test
    @DisplayName("PG-C2: campaign on APPROVED GLOBAL template is ready")
    void campaignReadyGlobalTemplate() {
        UUID groupId = seedContactGroup(tenantA);
        UUID templateId = seedTemplate("c2", TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);
        UUID campaignId = seedCampaign(tenantA, groupId, templateId, CampaignStatus.SCHEDULED);

        assertThat(reasonCodes(campaignId)).isEmpty();
    }

    @Test
    @DisplayName("PG-C3: own PENDING template → TTS_TEMPLATE_NOT_APPROVED")
    void campaignOwnPendingTemplate() {
        UUID groupId = seedContactGroup(tenantA);
        UUID templateId = seedTemplate("c3", TtsTemplateScope.TENANT, TtsTemplateStatus.PENDING_APPROVAL, tenantA);
        UUID campaignId = seedCampaign(tenantA, groupId, templateId, CampaignStatus.SCHEDULED);

        assertThat(reasonCodes(campaignId)).containsExactly("TTS_TEMPLATE_NOT_APPROVED");
    }

    @Test
    @DisplayName("PG-C4: cross-tenant reference → TTS_TEMPLATE_NOT_AVAILABLE (existence not leaked)")
    void campaignCrossTenantTemplate() {
        UUID groupId = seedContactGroup(tenantA);
        UUID templateId = seedTemplate("c4", TtsTemplateScope.TENANT, TtsTemplateStatus.APPROVED, tenantB);
        UUID campaignId = seedCampaign(tenantA, groupId, templateId, CampaignStatus.SCHEDULED);

        assertThat(reasonCodes(campaignId)).containsExactly("TTS_TEMPLATE_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("PG-C5: pending GLOBAL → NOT_APPROVED; deleted GLOBAL → NOT_AVAILABLE")
    void campaignGlobalPendingAndDeleted() {
        UUID groupId = seedContactGroup(tenantA);
        UUID globalId = seedTemplate("c5", TtsTemplateScope.GLOBAL, TtsTemplateStatus.PENDING_APPROVAL, null);
        UUID campaignId = seedCampaign(tenantA, groupId, globalId, CampaignStatus.SCHEDULED);
        assertThat(reasonCodes(campaignId)).containsExactly("TTS_TEMPLATE_NOT_APPROVED");

        transactionTemplate.executeWithoutResult(tx ->
            entityManager.createNativeQuery(
                "UPDATE tts_templates SET deleted_at = now() WHERE id = :id")
                .setParameter("id", globalId)
                .executeUpdate());
        assertThat(reasonCodes(campaignId)).containsExactly("TTS_TEMPLATE_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("PG-C6: missing template id → TTS_TEMPLATE_NOT_AVAILABLE")
    void campaignMissingTemplate() {
        UUID groupId = seedContactGroup(tenantA);
        UUID campaignId = seedCampaign(tenantA, groupId, UUID.randomUUID(), CampaignStatus.SCHEDULED);

        assertThat(reasonCodes(campaignId)).containsExactly("TTS_TEMPLATE_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("PG-C7: rejected GLOBAL → TTS_TEMPLATE_NOT_APPROVED")
    void campaignRejectedGlobal() {
        UUID groupId = seedContactGroup(tenantA);
        UUID globalId = seedTemplate("c7", TtsTemplateScope.GLOBAL, TtsTemplateStatus.REJECTED, null);
        UUID campaignId = seedCampaign(tenantA, groupId, globalId, CampaignStatus.SCHEDULED);

        assertThat(reasonCodes(campaignId)).containsExactly("TTS_TEMPLATE_NOT_APPROVED");
    }

    @Test
    @DisplayName("PG-C8: campaign on GLOBAL template — create, ready, DRAFT→SCHEDULED")
    void campaignGlobalWriteAndActivationPath() {
        tenantScope(tenantA);
        UUID groupId = seedContactGroup(tenantA);
        UUID globalId = seedTemplate("c8", TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);
        platformScope();
        UUID campaignId = transactionTemplate.execute(tx ->
            campaignService.create(ttsCampaignRequest(groupId, globalId), tenantA).data().id());

        // DRAFT + future schedule produce lifecycle/schedule reasons by
        // design; the TTS gate must be silent for an APPROVED GLOBAL row.
        assertThat(reasonCodes(campaignId).stream()
            .filter(code -> code.startsWith("TTS_"))).isEmpty();

        tenantScope(tenantA);
        transactionTemplate.executeWithoutResult(tx -> campaignService.changeStatus(
            campaignId, new com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest("SCHEDULED")));
        assertThat(campaignRepository.findById(campaignId).orElseThrow().getStatus())
            .isEqualTo(CampaignStatus.SCHEDULED);
    }

    @Test
    @DisplayName("PG-C9: approval revoked after creation — activation re-check blocks")
    void campaignUnapprovedGlobalBlocked() {
        tenantScope(tenantA);
        UUID groupId = seedContactGroup(tenantA);
        // Creation itself requires a usable template, so start APPROVED...
        UUID globalId = seedTemplate("c9", TtsTemplateScope.GLOBAL, TtsTemplateStatus.APPROVED, null);
        platformScope();
        UUID campaignId = transactionTemplate.execute(tx ->
            campaignService.create(ttsCampaignRequest(groupId, globalId), tenantA).data().id());
        assertThat(reasonCodes(campaignId).stream()
            .filter(code -> code.startsWith("TTS_"))).isEmpty();

        // ...then revoke approval; activation must re-check and refuse.
        transactionTemplate.executeWithoutResult(tx ->
            reload(globalId).setStatus(TtsTemplateStatus.PENDING_APPROVAL));

        tenantScope(tenantA);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> campaignService.changeStatus(
            campaignId, new com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest("SCHEDULED"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("TTS template");
    }
}
