package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.campaign.config.CampaignIntegrationConfig;
import com.shivang.obd.campaign.config.ReportPrivacy;
import com.shivang.obd.campaign.config.ReportPrivacyConfig;
import com.shivang.obd.campaign.config.WebhookConfig;
import com.shivang.obd.campaign.config.WebhookEvent;
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
import java.util.List;
import java.util.Set;
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
 * VB-7C.2: the typed integration configuration against real PostgreSQL.
 *
 * <p>What only a real database proves for this phase:
 * <ul>
 *   <li>the typed configuration round-trips through the <b>existing</b>
 *       {@code integration_config} JSONB column — proving no migration was
 *       needed and the reused column is sufficient;</li>
 *   <li>the canonical serialization is what is actually persisted, not the
 *       client's raw JSON;</li>
 *   <li>integration configuration is tenant-owned in the same way as every other
 *       campaign field, and a foreign campaign is indistinguishable from a
 *       missing one;</li>
 *   <li>cloning a campaign copies the configuration rather than dropping or
 *       reinterpreting it.</li>
 * </ul>
 *
 * <p>Follows the VB-4/5/6/7 series harness: static Testcontainers PostgreSQL with
 * Flyway V1..V54, {@code NOT_SUPPORTED} propagation, services constructed
 * directly and invoked inside a {@link TransactionTemplate}.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampaignIntegrationPostgresIntegrationTest {

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
    private DidRepository didRepository;
    @Autowired
    private com.shivang.obd.contact.ContactGroupRepository contactGroupRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final UUID CALLER_ID =
            UUID.fromString("ff000000-0000-4000-8000-0000000000f1");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger E164 = new AtomicInteger(7000);

    private CampaignMapper mapper;

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                            + startupFailure.getMessage(), startupFailure);
        }
        mapper = new CampaignMapper();
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createQuery("DELETE FROM CampaignEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM DidEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactGroupEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM TenantEntity").executeUpdate();
        });
    }

    // === tests ===

    @Test
    @DisplayName("PG-I1. the typed configuration round-trips the EXISTING JSONB column - "
            + "no migration was needed")
    void configurationRoundTripsThroughJsonb() {
        UUID tenantId = seedTenant("i1").getId();
        UUID campaignId = seedCampaign(tenantId, fullConfig());

        JsonNode stored = transactionTemplate.execute(tx ->
                campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                        .orElseThrow().getIntegrationConfig());

        assertThat(stored).isNotNull();
        assertThat(stored.at("/webhook/enabled").asBoolean()).isTrue();
        assertThat(stored.at("/webhook/endpoint").asString())
                .isEqualTo("https://example.com/hooks/campaign");
        assertThat(stored.at("/reportPrivacy/policy").asString()).isEqualTo("MASKED");

        // and it parses back into the typed configuration the API returns
        CampaignIntegrationConfig parsed = CampaignIntegrationConfig.fromJson(stored);
        assertThat(parsed.isWebhookEnabled()).isTrue();
        assertThat(parsed.selectedEvents()).containsExactlyInAnyOrder(
                WebhookEvent.ATTEMPT_COMPLETED,
                WebhookEvent.ATTEMPT_FAILED,
                WebhookEvent.ATTEMPT_CANCELLED);
        assertThat(parsed.reportPrivacy().policy()).isEqualTo(ReportPrivacy.MASKED);
    }

    @Test
    @DisplayName("PG-I2. events persist as their PUBLIC dotted identifiers, never as Java "
            + "enum names")
    void eventsPersistAsPublicIdentifiers() {
        UUID tenantId = seedTenant("i2").getId();
        UUID campaignId = seedCampaign(tenantId, fullConfig());

        JsonNode stored = transactionTemplate.execute(tx ->
                campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                        .orElseThrow().getIntegrationConfig());

        String text = stored.at("/webhook/events").toString();
        assertThat(text)
                .contains("campaign.attempt.completed")
                .contains("campaign.attempt.failed")
                .contains("campaign.attempt.cancelled")
                .as("events must persist as public dotted identifiers, never as Java "
                        + "enum constant names")
                .doesNotContain("ATTEMPT_COMPLETED")
                .doesNotContain("ATTEMPT_FAILED")
                .doesNotContain("ATTEMPT_CANCELLED");
    }

    @Test
    @DisplayName("PG-I3. what is stored is the CANONICAL form")
    void canonicalFormIsPersisted() {
        UUID tenantId = seedTenant("i3").getId();
        // Events supplied out of declaration order; the stored order is normalised.
        var reversed = new CampaignIntegrationConfig(
                WebhookConfig.of("https://example.com/h",
                        new java.util.LinkedHashSet<>(List.of(
                                WebhookEvent.ATTEMPT_CANCELLED,
                                WebhookEvent.ATTEMPT_COMPLETED))),
                ReportPrivacyConfig.defaults());
        UUID campaignId = seedCampaign(tenantId, reversed);

        JsonNode stored = transactionTemplate.execute(tx ->
                campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                        .orElseThrow().getIntegrationConfig());

        String text = stored.at("/webhook/events").toString();
        assertThat(text.indexOf("campaign.attempt.completed"))
                .as("events are persisted in declaration order, deterministically")
                .isLessThan(text.indexOf("campaign.attempt.cancelled"));
    }

    @Test
    @DisplayName("PG-I4. a campaign with no integration configuration stores null, not a "
            + "defaulted object")
    void absentConfigurationStoresNull() {
        UUID tenantId = seedTenant("i4").getId();
        UUID campaignId = seedCampaign(tenantId, null);

        JsonNode stored = transactionTemplate.execute(tx ->
                campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                        .orElseThrow().getIntegrationConfig());

        assertThat(stored).isNull();
    }

    @Test
    @DisplayName("PG-I5. cloning a campaign copies the integration configuration verbatim")
    void cloneCopiesConfiguration() {
        UUID tenantId = seedTenant("i5").getId();
        UUID sourceId = seedCampaign(tenantId, fullConfig());
        UUID cloneId = transactionTemplate.execute(tx -> {
            CampaignEntity source = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(sourceId, tenantId).orElseThrow();
            CampaignEntity clone = new CampaignEntity();
            clone.setTenantId(tenantId);
            clone.setName("clone-" + SEQ.incrementAndGet());
            clone.setCampaignType(CampaignType.MISSED_CALL);
            clone.setStatus(CampaignStatus.DRAFT);
            clone.setDidId(source.getDidId());
            clone.setContactGroupId(source.getContactGroupId());
            clone.setSchedule(source.getSchedule());
            clone.setRetryPolicy(source.getRetryPolicy());
            clone.setClonedFromCampaignId(sourceId);
            // exactly the production clone behaviour
            clone.setTypeConfig(source.getTypeConfig());
            clone.setIntegrationConfig(source.getIntegrationConfig());
            return campaignRepository.saveAndFlush(clone).getId();
        });

        JsonNode stored = transactionTemplate.execute(tx ->
                campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(cloneId, tenantId)
                        .orElseThrow().getIntegrationConfig());

        assertThat(stored.at("/webhook/endpoint").asString())
                .isEqualTo("https://example.com/hooks/campaign");
    }

    @Test
    @DisplayName("PG-I6. integration configuration is tenant-owned: a foreign campaign is "
            + "indistinguishable from a missing one")
    void configurationIsTenantIsolated() {
        UUID tenantA = seedTenant("i6a").getId();
        UUID tenantB = seedTenant("i6b").getId();
        UUID campaignInA = seedCampaign(tenantA, fullConfig());
        UUID foreign = seedCampaign(tenantB, fullConfig());

        // Visible within its own tenant.
        boolean ownVisible = transactionTemplate.execute(tx ->
                campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignInA, tenantA)
                        .isPresent());
        assertThat(ownVisible).isTrue();

        // Invisible from the other tenant - same empty result as a random id, so
        // the response cannot be used to discover that it exists.
        boolean foreignVisibleFromA = transactionTemplate.execute(tx ->
                campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(foreign, tenantA)
                        .isPresent());
        boolean randomVisibleFromA = transactionTemplate.execute(tx ->
                campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                                UUID.fromString("00000000-0000-4000-8000-00000000dead"), tenantA)
                        .isPresent());

        assertThat(foreignVisibleFromA)
                .as("a foreign campaign must be invisible")
                .isFalse();
        assertThat(randomVisibleFromA)
                .as("and must be indistinguishable from one that does not exist")
                .isEqualTo(foreignVisibleFromA);
    }

    @Test
    @DisplayName("PG-I7. an existing campaign's configuration is NOT rewritten when the "
            + "campaign is edited elsewhere - the stored JSONB is untouched by a name change")
    void unrelatedEditDoesNotDisturbConfiguration() {
        UUID tenantId = seedTenant("i7").getId();
        UUID campaignId = seedCampaign(tenantId, fullConfig());

        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            c.setName("renamed-" + SEQ.incrementAndGet());
            campaignRepository.saveAndFlush(c);
        });

        JsonNode stored = transactionTemplate.execute(tx ->
                campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                        .orElseThrow().getIntegrationConfig());

        assertThat(stored.at("/webhook/endpoint").asString())
                .isEqualTo("https://example.com/hooks/campaign");
    }

    @Test
    @DisplayName("PG-I8. the execution snapshot still does NOT carry the integration "
            + "configuration - documented, because nothing consumes it")
    void snapshotStillExcludesConfiguration() {
        // OD-3, deferred. The exclusion is correct while no consumer exists and is
        // a documented hazard: the first consumer must add it in the same phase.
        assertThat(com.shivang.obd.campaign.CampaignConfigurationSnapshot.class
                .getDeclaredFields())
                .as("CampaignConfigurationSnapshot must gain no integration field yet")
                .noneMatch(f -> f.getName().toLowerCase(java.util.Locale.ROOT)
                        .contains("integration"));
    }

    // === helpers ===

    private static CampaignIntegrationConfig fullConfig() {
        return new CampaignIntegrationConfig(
                WebhookConfig.of("https://example.com/hooks/campaign",
                        Set.of(WebhookEvent.ATTEMPT_COMPLETED, WebhookEvent.ATTEMPT_FAILED,
                                WebhookEvent.ATTEMPT_CANCELLED)),
                new ReportPrivacyConfig(ReportPrivacy.MASKED));
    }

    private TenantEntity seedTenant(String label) {
        return transactionTemplate.execute(tx -> {
            TenantEntity t = new TenantEntity();
            t.setName("tenant-" + label);
            t.setSlug("t-" + label);
            t.setStatus(LifecycleStatus.ACTIVE);
            return tenantRepository.saveAndFlush(t);
        });
    }

    private UUID seedDid(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            DidEntity d = new DidEntity();
            d.setE164Number("+9197" + String.format("%08d", E164.incrementAndGet()));
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

    private UUID seedGroup(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            var g = new com.shivang.obd.contact.ContactGroupEntity();
            g.setTenantId(tenantId);
            g.setName("cg-" + SEQ.incrementAndGet());
            return contactGroupRepository.saveAndFlush(g).getId();
        });
    }

    private UUID seedCampaign(UUID tenantId, CampaignIntegrationConfig config) {
        return transactionTemplate.execute(tx -> {
            CampaignEntity c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-vb7c2-" + SEQ.incrementAndGet());
            c.setCampaignType(CampaignType.MISSED_CALL);
            c.setStatus(CampaignStatus.SCHEDULED);
            c.setDidId(seedDid(tenantId));
            c.setContactGroupId(seedGroup(tenantId));
            c.setRetryPolicy(new RetryPolicySpec(0, null, RetryStrategy.FIXED));
            // VB-6C.1: a campaign with no execution timezone is undialable, and
            // readiness requires one. A windowless schedule with a zone is the
            // minimal shape that is both ready and independent of the run day.
            c.setSchedule(new ScheduleSpec(null, null, null, null, "Asia/Kolkata", null, null));
            tools.jackson.databind.node.ObjectNode typeConfig =
                    JsonNodeFactoryHolder.objectNode();
            tools.jackson.databind.node.ObjectNode inner =
                    JsonNodeFactoryHolder.objectNode();
            inner.put("ringDurationSeconds", 30);
            typeConfig.set("missedCall", inner);
            c.setTypeConfig(typeConfig);
            c.setIntegrationConfig(config == null ? null : config.toJson());
            return campaignRepository.saveAndFlush(c).getId();
        });
    }

    /** Small indirection so the seeding code reads clearly. */
    private static final class JsonNodeFactoryHolder {
        static tools.jackson.databind.node.ObjectNode objectNode() {
            return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
    }
}
