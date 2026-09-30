package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.campaign.dto.CreateCampaignRequest;
import com.shivang.obd.campaign.dto.ExecuteCampaignRequest;
import com.shivang.obd.campaign.dto.RetryPolicyConfig;
import com.shivang.obd.campaign.dto.RetryRuleConfig;
import com.shivang.obd.campaign.dto.ScheduleConfig;
import com.shivang.obd.campaign.dto.UpdateCampaignRequest;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.audio.AudioAssetEntity;
import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.audio.AudioAssetStatus;
import com.shivang.obd.did.AllocationSource;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.did.NumberType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-6D.2 — the retry policy against real PostgreSQL and the real service
 * stack, on a clean Flyway chain (V1..V49).
 *
 * <p>The properties that can only be proven with a real database, because each
 * depends on the JSONB column round-tripping through Hibernate and on the
 * snapshot genuinely being immutable:
 *
 * <ul>
 *   <li>the rules persist on the campaign and survive a real read;</li>
 *   <li>they are frozen into the execution snapshot <em>verbatim</em>;</li>
 *   <li>editing the campaign afterwards does NOT change an existing
 *       execution's rules (the core VB-6D.2 invariant);</li>
 *   <li>two executions created around a campaign edit carry different
 *       policies;</li>
 *   <li>a campaign with no rules round-trips as {@code null}, so the
 *       pre-VB-6D.2 behaviour is preserved bit for bit.</li>
 * </ul>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryPolicySnapshotPostgresIntegrationTest {

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

    @org.springframework.test.context.DynamicPropertySource
    static void registerProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        if (startupFailure != null) {
            return;
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignExecutionRepository executionRepository;
    @Autowired private CampaignExecutionConfigurationRepository snapshotRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private com.shivang.obd.contact.ContactGroupRepository contactGroupRepository;
    @Autowired private DidRepository didRepository;
    @Autowired private AudioAssetRepository audioAssetRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    // A full-day window keeps the suite independent of the wall-clock time it runs
    // at: a 09:00-18:00 window makes readiness fail for any run after 18:00 in
    // the campaign timezone, which is a time bomb rather than a test of retry
    // policy.
    private static final LocalTime FULL_DAY_START = LocalTime.of(0, 0);
    private static final LocalTime FULL_DAY_END = LocalTime.of(23, 59);

    private static final UUID CALLER_ID =
            UUID.fromString("66666666-0000-4000-8000-000000000001");
    private static final AtomicInteger SEQ = new AtomicInteger(400);

    private CampaignService campaignService;
    private CampaignExecutionService executionService;
    private CampaignRuntimeConfigResolver runtimeConfigResolver;
    private RetryPolicyService retryPolicyService;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                            + startupFailure.getMessage(), startupFailure);
        }
        com.shivang.obd.authz.AuthorizationService allowAll =
                new com.shivang.obd.authz.AuthorizationService(
                        List.of(), null, null, null, List.of()) {
                    @Override
                    public void requireCapability(UUID userId, String capabilityKey,
                            com.shivang.obd.authz.AccessCheck target) {
                    }
                };
        CurrentUserProvider currentUser = new CurrentUserProvider() {
            @Override
            public Optional<AuthenticatedUser> current() {
                return Optional.of(new AuthenticatedUser(CALLER_ID, "retry@test.local", null));
            }
        };
        CampaignConfigurationService configurationService = new CampaignConfigurationService(
                snapshotRepository, new com.shivang.obd.campaign.config.CampaignTypeConfigValidator());
        runtimeConfigResolver = new CampaignRuntimeConfigResolver(configurationService);
        CampaignReadinessService readinessService = new CampaignReadinessService(
                campaignRepository, allowAll, currentUser, contactGroupRepository,
                new CampaignResourceValidationService(didRepository, audioAssetRepository, null),
                tenantRepository);
        campaignService = new CampaignService(
                campaignRepository, allowAll,
                new com.shivang.obd.campaign.event.CampaignEventPublisher(
                        new org.springframework.context.support.StaticApplicationContext()),
                currentUser, new CampaignMapper(), tenantRepository, contactGroupRepository,
                new CampaignResourceValidationService(didRepository, audioAssetRepository, null),
                new CampaignLifecyclePolicy());
        executionService = new CampaignExecutionService(
                campaignRepository, executionRepository, allowAll, currentUser,
                readinessService, tenantRepository, configurationService);
        retryPolicyService = new RetryPolicyService();
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    // === fixtures ===

    private UUID seedTenant() {
        return tx().execute(status -> {
            TenantEntity t = new TenantEntity();
            t.setName("retry-tenant-" + SEQ.incrementAndGet());
            t.setSlug("rt-" + SEQ.incrementAndGet());
            t.setStatus(com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE);
            return tenantRepository.saveAndFlush(t).getId();
        });
    }

    private UUID seedDid(UUID tenantId) {
        return tx().execute(t -> {
            DidEntity d = new DidEntity();
            d.setE164Number("+9198" + String.format("%08d", SEQ.incrementAndGet()));
            d.setCountryCode("+91");
            d.setNumberType(NumberType.MOBILE);
            d.setProvider("TATA");
            d.setStatus(DidStatus.ACTIVE);
            d.setAllocationState(AllocationState.ASSIGNED);
            d.setAllocationSource(AllocationSource.PLATFORM);
            d.setTenantId(tenantId);
            return didRepository.saveAndFlush(d).getId();
        });
    }

    private UUID seedAudio(UUID tenantId) {
        return tx().execute(t -> {
            AudioAssetEntity a = new AudioAssetEntity();
            a.setTenantId(tenantId);
            a.setName("retry-asset-" + SEQ.incrementAndGet());
            a.setFileName("r-" + SEQ.get() + ".wav");
            a.setContentType("audio/wav");
            a.setFileSize(1024L);
            a.setStorageReference("s3://retry/" + SEQ.incrementAndGet() + ".wav");
            a.setStatus(AudioAssetStatus.APPROVED);
            return audioAssetRepository.saveAndFlush(a).getId();
        });
    }

    private static RetryPolicyConfig retryPolicy(Integer maxAttempts, Integer intervalSeconds,
                                                RetryRuleConfig... rules) {
        return new RetryPolicyConfig(maxAttempts, intervalSeconds, RetryStrategy.FIXED,
                rules.length == 0 ? null : List.of(rules));
    }

    private static RetryRuleConfig rule(RetryRuleCategory category, int maxRetries,
                                        String delay) {
        return new RetryRuleConfig(category, Boolean.TRUE, maxRetries, delay);
    }

    private UUID createCampaign(UUID tenantId, RetryPolicyConfig retryPolicy) {
        com.shivang.obd.authz.context.OrganizationContextHolder
                .setAuthenticated(CALLER_ID, tenantId, null);
        CreateCampaignRequest request = new CreateCampaignRequest(
                "retry-c-" + SEQ.incrementAndGet(), null, CampaignType.PLAYFILE, null,
                null, seedDid(tenantId),
                ContentMode.AUDIO, seedAudio(tenantId), null,
                new ScheduleConfig(LocalDate.of(2026, 9, 1),
                        FULL_DAY_START, FULL_DAY_END, "Asia/Kolkata", Set.of(), null),
                retryPolicy, null, null, false, null);
        return campaignService.create(request, null).data().id();
    }

    private UUID createExecution(UUID tenantId, UUID campaignId) {
        com.shivang.obd.authz.context.OrganizationContextHolder
                .setAuthenticated(CALLER_ID, tenantId, null);
        // Schedule only when needed: a second execution for an already-SCHEDULED
        // campaign is a no-op, and SCHEDULED -> SCHEDULED is not a legal
        // transition.
        CampaignStatus status = tx().execute(t -> campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow()
                .getStatus());
        if (status == CampaignStatus.DRAFT) {
            tx().executeWithoutResult(t -> campaignService.changeStatus(campaignId,
                    new com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest("SCHEDULED")));
        }
        return executionService.execute(campaignId, new ExecuteCampaignRequest(null))
                .data().id();
    }

    /** The rules the execution's immutable snapshot actually carries. */
    private List<RetryRule> snapshotRules(UUID executionId) {
        return tx().execute(t -> runtimeConfigResolver
                .resolve(executionRepository.findByIdAndDeletedAtIsNull(executionId).orElseThrow())
                .retryPolicy().getRules());
    }

    private RetryPolicySpec snapshotPolicy(UUID executionId) {
        return tx().execute(t -> runtimeConfigResolver
                .resolve(executionRepository.findByIdAndDeletedAtIsNull(executionId).orElseThrow())
                .retryPolicy());
    }

    private Integer campaignMaxRetries(UUID tenantId, UUID campaignId) {
        return tx().execute(t -> campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow()
                .getRetryPolicy().getMaxAttempts());
    }

    /** The rules the live campaign currently stores. */
    private List<RetryRule> campaignRules(UUID tenantId, UUID campaignId) {
        return tx().execute(t -> campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow()
                .getRetryPolicy().getRules());
    }

    /** Applies a new retry policy through the REAL update path. */
    private void updateCampaignRetryPolicy(UUID tenantId, UUID campaignId,
                                           RetryPolicyConfig policy) {
        com.shivang.obd.authz.context.OrganizationContextHolder
                .setAuthenticated(CALLER_ID, tenantId, null);
        tx().executeWithoutResult(t -> {
            CampaignEntity current = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            UpdateCampaignRequest request = new UpdateCampaignRequest(
                    current.getName(), current.getDescription(), current.getRunMode(),
                    current.getContactGroupId(), current.getDidId(),
                    current.getContentMode(), current.getAudioAssetId(), current.getTtsTemplateId(),
                    new ScheduleConfig(
                            current.getSchedule().getStartDate(),
                            current.getSchedule().getStartTime(),
                            current.getSchedule().getEndTime(),
                            current.getSchedule().getTimezone(),
                            current.getSchedule().getAllowedDaysOfWeek(),
                            current.getSchedule().getHolidayCalendarId()),
                    policy, null, null, null);
            campaignService.update(campaignId, request);
        });
    }

    // === I. snapshot immutability ===

    @Test
    @DisplayName("RPS-1: retry rules persist on the campaign and freeze into the execution snapshot")
    void rulesPersistAndFreezeIntoSnapshot() {
        UUID tenantId = seedTenant();
        RetryPolicyConfig policy = retryPolicy(0, null,
                rule(RetryRuleCategory.NO_ANSWER, 3, "10:00"),
                rule(RetryRuleCategory.BUSY, 1, "02:00"));
        UUID campaignId = createCampaign(tenantId, policy);

        // Campaign stores the configured rules.
        assertThat(campaignRules(tenantId, campaignId)).hasSize(2);

        UUID executionId = createExecution(tenantId, campaignId);

        // Snapshot carries them verbatim.
        List<RetryRule> frozen = snapshotRules(executionId);
        assertThat(frozen).hasSize(2);
        assertThat(frozen).extracting(RetryRule::category)
                .containsExactlyInAnyOrder(RetryRuleCategory.NO_ANSWER, RetryRuleCategory.BUSY);
        RetryRule noAnswer = frozen.stream()
                .filter(r -> r.category() == RetryRuleCategory.NO_ANSWER).findFirst().orElseThrow();
        assertThat(noAnswer.effectiveMaxRetries()).isEqualTo(3);
        assertThat(noAnswer.retryDelay().toString()).isEqualTo("10:00");
    }

    @Test
    @DisplayName("RPS-2: editing the campaign after execution creation does NOT change the snapshot")
    void campaignEditDoesNotChangeExistingSnapshot() {
        UUID tenantId = seedTenant();
        UUID campaignId = createCampaign(tenantId,
                retryPolicy(0, null, rule(RetryRuleCategory.NO_ANSWER, 3, "10:00")));
        UUID executionId = createExecution(tenantId, campaignId);
        assertThat(snapshotPolicy(executionId).ruleFor(RetryRuleCategory.NO_ANSWER)
                .effectiveMaxRetries()).isEqualTo(3);

        // Campaign is SCHEDULED and therefore not editable: unlock, change, re-lock.
        com.shivang.obd.authz.context.OrganizationContextHolder
                .setAuthenticated(CALLER_ID, tenantId, null);
        tx().executeWithoutResult(t -> campaignService.changeStatus(campaignId,
                new com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest("DRAFT")));
        updateCampaignRetryPolicy(tenantId, campaignId,
                retryPolicy(0, null, rule(RetryRuleCategory.NO_ANSWER, 0, null)));
        tx().executeWithoutResult(t -> campaignService.changeStatus(campaignId,
                new com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest("SCHEDULED")));

        // Campaign now says NO_ANSWER is not retryable...
        List<RetryRule> afterEdit = campaignRules(tenantId, campaignId);
        assertThat(afterEdit).hasSize(1);
        assertThat(afterEdit.get(0).effectiveMaxRetries()).isEqualTo(0);

        // ...but the existing execution still runs the frozen 3.
        assertThat(snapshotPolicy(executionId).ruleFor(RetryRuleCategory.NO_ANSWER)
                .effectiveMaxRetries())
                .as("an execution must never change retry behaviour because the campaign was edited")
                .isEqualTo(3);
        assertThat(snapshotRules(executionId).get(0).retryDelay().toString()).isEqualTo("10:00");
    }

    @Test
    @DisplayName("RPS-3: two executions around a campaign edit carry different frozen policies")
    void differentExecutionsCarryDifferentPolicies() {
        UUID tenantId = seedTenant();
        UUID campaignId = createCampaign(tenantId,
                retryPolicy(0, null, rule(RetryRuleCategory.NO_ANSWER, 3, "10:00")));
        UUID e1 = createExecution(tenantId, campaignId);

        com.shivang.obd.authz.context.OrganizationContextHolder
                .setAuthenticated(CALLER_ID, tenantId, null);
        tx().executeWithoutResult(t -> campaignService.changeStatus(campaignId,
                new com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest("DRAFT")));
        updateCampaignRetryPolicy(tenantId, campaignId,
                retryPolicy(0, null, rule(RetryRuleCategory.NO_ANSWER, 1, "01:00")));
        tx().executeWithoutResult(t -> campaignService.changeStatus(campaignId,
                new com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest("SCHEDULED")));
        settle(e1);

        UUID e2 = createExecution(tenantId, campaignId);

        // The runtime sees two genuinely different policies, and each is the one
        // that was configured when that execution was created.
        RetryPolicyService policy = retryPolicyService;
        Instant failedAt = Instant.parse("2026-09-27T10:00:00Z");

        RetryDecision d1 = policy.evaluate(snapshotPolicy(e1), "NO_ANSWER", 1, failedAt);
        RetryDecision d2 = policy.evaluate(snapshotPolicy(e2), "NO_ANSWER", 1, failedAt);

        assertThat(d1.maxTotalAttempts()).isEqualTo(4);
        assertThat(d1.nextEligibleAt()).isEqualTo(failedAt.plusSeconds(600));
        assertThat(d2.maxTotalAttempts()).isEqualTo(2);
        assertThat(d2.nextEligibleAt()).isEqualTo(failedAt.plusSeconds(60));
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
        tx().executeWithoutResult(t -> {
            CampaignExecution e = executionRepository
                    .findByIdAndDeletedAtIsNull(executionId).orElseThrow();
            e.setStatus(CampaignExecutionStatus.COMPLETED);
            executionRepository.saveAndFlush(e);
        });
    }

    @Test
    @DisplayName("RPS-4: a campaign with no rules round-trips as NULL (pre-VB-6D.2 behaviour preserved)")
    void noRulesRoundTripsAsNull() {
        UUID tenantId = seedTenant();
        UUID campaignId = createCampaign(tenantId, new RetryPolicyConfig(2, 300, null, null));
        UUID executionId = createExecution(tenantId, campaignId);

        assertThat(snapshotRules(executionId))
                .as("no rules configured must stay null, not an empty list")
                .isNull();

        // And the legacy flat policy still governs exactly as before.
        RetryPolicySpec snapshot = snapshotPolicy(executionId);
        assertThat(snapshot.getMaxAttempts()).isEqualTo(2);
        assertThat(snapshot.getIntervalSeconds()).isEqualTo(300);
        assertThat(snapshot.hasRules()).isFalse();

        RetryDecision decision = retryPolicyService.evaluate(snapshot, "NO_ANSWER", 1,
                Instant.parse("2026-09-27T10:00:00Z"));
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.maxTotalAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("RPS-5: the rules column exists on BOTH the campaign and the immutable snapshot")
    void rulesColumnOnBothTables() {
        for (String table : List.of("campaigns", "campaign_execution_configurations")) {
            Number count = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name = ? AND column_name = 'retry_rules'")
                    .setParameter(1, table)
                    .getSingleResult();
            assertThat(count.intValue())
                    .as("%s must carry retry_rules", table)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("RPS-6: the migration chain reaches V49 on a clean database")
    void migrationChainReachesV49() {
        Object version = entityManager.createNativeQuery(
                "SELECT version FROM flyway_schema_history WHERE version = '49'")
                .getSingleResult();
        assertThat(version).isEqualTo("49");
    }

    @Test
    @DisplayName("RPS-7: an invalid policy is rejected before it can reach the snapshot")
    void invalidPolicyRejectedAtTheApiBoundary() {
        UUID tenantId = seedTenant();
        com.shivang.obd.authz.context.OrganizationContextHolder
                .setAuthenticated(CALLER_ID, tenantId, null);
        CreateCampaignRequest request = new CreateCampaignRequest(
                "bad-retry", null, CampaignType.PLAYFILE, null,
                null, seedDid(tenantId), ContentMode.AUDIO, seedAudio(tenantId), null,
                new ScheduleConfig(LocalDate.of(2026, 9, 1),
                        FULL_DAY_START, FULL_DAY_END, "Asia/Kolkata", Set.of(), null),
                // duplicate category: bean validation cannot express this
                retryPolicy(0, null,
                        rule(RetryRuleCategory.BUSY, 1, "01:00"),
                        rule(RetryRuleCategory.BUSY, 2, "02:00")),
                null, null, false, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> campaignService.create(request, null))
                .isInstanceOf(com.shivang.obd.common.exception.BusinessException.class)
                .hasMessageContaining("duplicate retry rule");
    }
}
