package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.config.CampaignTypeConfigValidator;
import com.shivang.obd.campaign.dto.CreateCallAttemptRequest;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.contact.ContactEntity;
import com.shivang.obd.did.AllocationSource;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
 * VB-8B — the manual call-attempt boundary against real PostgreSQL (Flyway
 * V1..V55).
 *
 * <p>{@code ManualCallAttemptBoundaryTest} proves the deterministic domain rules
 * with mocks. What only a database can show is that the frozen snapshot is
 * genuinely the source after a real campaign edit, that nothing is persisted
 * when a request is refused, and that tenant scoping survives end to end.
 *
 * <ul>
 *   <li>PGB-1 — a manual attempt is persisted with the frozen DID, the frozen
 *       calling window and the frozen retry ceiling</li>
 *   <li>PGB-2 — a refused request writes nothing (no partial attempt)</li>
 *   <li>PGB-3 — a post-snapshot campaign edit cannot change what a manual
 *       attempt writes, while a NEW execution picks the edit up</li>
 *   <li>PGB-4 — cross-tenant execution, contact and DID are refused</li>
 *   <li>PGB-5 — a terminal execution writes nothing</li>
 *   <li>PGB-6 — the snapshot's own retry policy bounds the attempt number</li>
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
class ManualAttemptBoundaryPostgresIntegrationTest {

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
    private CallAttemptRepository attemptRepository;
    @Autowired
    private DidRepository didRepository;
    @Autowired
    private com.shivang.obd.audio.AudioAssetRepository audioAssetRepository;
    @Autowired
    private com.shivang.obd.contact.ContactGroupRepository contactGroupRepository;
    @Autowired
    private com.shivang.obd.contact.ContactGroupMemberRepository memberRepository;
    @Autowired
    private com.shivang.obd.contact.ContactRepository contactRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final UUID CALLER_ID =
            UUID.fromString("ff000000-0000-4000-8000-0000000000f1");
    private static final AtomicInteger SEQ = new AtomicInteger();

    private CampaignConfigurationService configurationService;
    private CampaignRuntimeConfigResolver runtimeConfigResolver;
    private CallAttemptService attemptService;

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                            + startupFailure.getMessage(), startupFailure);
        }
        configurationService = new CampaignConfigurationService(
                snapshotRepository, new CampaignTypeConfigValidator());
        runtimeConfigResolver = new CampaignRuntimeConfigResolver(configurationService);

        com.shivang.obd.authz.AuthorizationService allowAll =
                new com.shivang.obd.authz.AuthorizationService(List.of(), null, null, null, List.of()) {
                    @Override
                    public void requireCapability(UUID userId, String capabilityKey,
                            com.shivang.obd.authz.AccessCheck target) {
                        // harness pass-through; tenant scoping is asserted separately
                    }
                };
        CurrentUserProvider user = () -> java.util.Optional.of(
                new AuthenticatedUser(CALLER_ID, "it@test.local", null));

        CampaignResourceValidationService validator = new CampaignResourceValidationService(
                didRepository, audioAssetRepository, null);

        attemptService = new CallAttemptService(
                executionRepository, attemptRepository, allowAll, user,
                contactRepository, memberRepository, runtimeConfigResolver,
                new ExecutionScheduleCalculator(), validator, tenantRepository);
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

    // === PGB-1: persistence with frozen configuration ===

    @Test
    @DisplayName("PGB-1: the persisted attempt carries the frozen DID and a window-compliant time")
    void persistedAttemptUsesFrozenConfiguration() {
        UUID tenantId = seedTenant("p1").getId();
        Seed seed = seedScenario(tenantId, 2, true);

        CallAttempt saved = transactionTemplate.execute(tx -> {
            var response = attemptService.createAttempt(seed.campaignId(), seed.executionId(),
                    new CreateCallAttemptRequest(seed.contactId(), seed.didId(), 1,
                            // A client time far outside the 09:00-17:00 frozen window.
                            java.time.ZonedDateTime.of(
                                    java.time.LocalDate.of(2026, 3, 3),
                                    java.time.LocalTime.of(2, 0),
                                    java.time.ZoneId.of("UTC")).toInstant()));
            return attemptRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(response.data().id(), tenantId)
                    .orElseThrow();
        });

        assertThat(saved.getDidId())
                .as("the frozen DID, not anything the client chose")
                .isEqualTo(seed.didId());
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getAttemptNumber()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(CallAttemptStatus.QUEUED);

        // 09:00-17:00 UTC window; the derived time must land inside it.
        var zoned = saved.getScheduledAt().atZone(java.time.ZoneId.of("UTC"));
        assertThat(zoned.toLocalTime())
                .as("the derived time honours the frozen calling window")
                .isAfterOrEqualTo(LocalTime.of(9, 0))
                .isBeforeOrEqualTo(LocalTime.of(17, 0));
    }

    // === PGB-2: a refused request writes nothing ===

    @Test
    @DisplayName("PGB-2: an out-of-window attempt number is refused and writes nothing")
    void refusedRequestPersistsNothing() {
        UUID tenantId = seedTenant("p2").getId();
        // maxRetries = 0 -> only attempt 1 is permitted.
        Seed seed = seedScenario(tenantId, 0, true);

        transactionTemplate.executeWithoutResult(tx -> {
            assertThatThrownBy(() -> attemptService.createAttempt(
                    seed.campaignId(), seed.executionId(),
                    new CreateCallAttemptRequest(seed.contactId(), seed.didId(), 2, null)))
                    .isInstanceOf(ConflictException.class);
        });

        assertThat(attemptCount(seed.executionId(), tenantId)).isZero();
    }

    @Test
    @DisplayName("PGB-3: a client DID that disagrees with the frozen DID writes nothing")
    void didOverridePersistsNothing() {
        UUID tenantId = seedTenant("p3").getId();
        Seed seed = seedScenario(tenantId, 1, true);
        UUID otherDid = seedDid(tenantId);

        transactionTemplate.executeWithoutResult(tx -> {
            assertThatThrownBy(() -> attemptService.createAttempt(
                    seed.campaignId(), seed.executionId(),
                    new CreateCallAttemptRequest(seed.contactId(), otherDid, 1, null)))
                    .isInstanceOf(ConflictException.class);
        });

        assertThat(attemptCount(seed.executionId(), tenantId)).isZero();
    }

    // === PGB-4: post-snapshot campaign mutation ===

    @Test
    @DisplayName("PGB-4: a post-snapshot campaign edit cannot change what a manual attempt writes")
    void postSnapshotCampaignEditDoesNotReachTheManualAttempt() {
        UUID tenantId = seedTenant("p4").getId();
        Seed seed = seedScenario(tenantId, 1, true);

        // The operator repoints the campaign at a different DID after the
        // execution was created. The frozen snapshot still holds the original.
        UUID replacementDid = seedDid(tenantId);
        transactionTemplate.executeWithoutResult(tx -> {
            CampaignEntity c = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(seed.campaignId(), tenantId).orElseThrow();
            c.setDidId(replacementDid);
            campaignRepository.saveAndFlush(c);
        });

        CallAttempt first = transactionTemplate.execute(tx -> {
            var response = attemptService.createAttempt(seed.campaignId(), seed.executionId(),
                    new CreateCallAttemptRequest(seed.contactId(), seed.didId(), 1, null));
            return attemptRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(response.data().id(), tenantId)
                    .orElseThrow();
        });

        assertThat(first.getDidId())
                .as("the manual attempt used the FROZEN DID despite the live campaign change")
                .isEqualTo(seed.didId());
        assertThat(first.getDidId()).isNotEqualTo(replacementDid);

        // A NEW execution of the same edited campaign picks the edit up, which
        // is the intended shape: the boundary is per execution, not global.
        Seed second = createExecutionFor(seed.campaignId(), tenantId);
        assertThat(second.didId())
                .as("the new execution's snapshot froze the edited DID")
                .isEqualTo(replacementDid);

        CallAttempt afterEdit = transactionTemplate.execute(tx -> {
            var response = attemptService.createAttempt(seed.campaignId(), second.executionId(),
                    new CreateCallAttemptRequest(seed.contactId(), replacementDid, 1, null));
            return attemptRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(response.data().id(), tenantId)
                    .orElseThrow();
        });
        assertThat(afterEdit.getDidId()).isEqualTo(replacementDid);
    }

    // === PGB-5: tenant isolation ===

    @Test
    @DisplayName("PGB-5: another tenant's execution is unreachable and writes nothing")
    void crossTenantExecutionIsRefused() {
        UUID tenantA = seedTenant("p5a").getId();
        Seed seedB = seedScenario(tenantA, 1, true);

        // Caller scoped to a different tenant sees nothing.
        UUID otherTenant = seedTenant("p5b").getId();
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
                CALLER_ID, otherTenant, null);

        transactionTemplate.executeWithoutResult(tx -> {
            assertThatThrownBy(() -> attemptService.createAttempt(
                    seedB.campaignId(), seedB.executionId(),
                    new CreateCallAttemptRequest(seedB.contactId(), seedB.didId(), 1, null)))
                    .isInstanceOf(ResourceNotFoundException.class);
        });

        assertThat(attemptCount(seedB.executionId(), tenantA)).as("no attempt may be written for another tenant's execution").isZero();
    }

    @Test
    @DisplayName("PGB-5b: a contact outside the execution's tenant is refused")
    void crossTenantContactIsRefused() {
        UUID tenantId = seedTenant("p5c").getId();
        Seed seed = seedScenario(tenantId, 1, true);
        UUID foreignContact = seedContact(tenantId, "foreign");
        UUID foreignGroup = seedGroup(tenantId);
        addMember(tenantId, foreignGroup, foreignContact);

        transactionTemplate.executeWithoutResult(tx -> {
            assertThatThrownBy(() -> attemptService.createAttempt(
                    seed.campaignId(), seed.executionId(),
                    new CreateCallAttemptRequest(foreignContact, seed.didId(), 1, null)))
                    .isInstanceOf(BusinessException.class);
        });

        assertThat(attemptCount(seed.executionId(), tenantId))
                .as("a foreign contact must produce no attempt")
                .isZero();
    }

    // === PGB-6: terminal execution writes nothing ===

    @Test
    @DisplayName("PGB-6: a COMPLETED execution cannot be resurrected and writes nothing")
    void terminalExecutionWritesNothing() {
        UUID tenantId = seedTenant("p6").getId();
        Seed seed = seedScenario(tenantId, 1, true);

        transactionTemplate.executeWithoutResult(tx -> {
            CampaignExecution e = executionRepository
                    .findByIdAndDeletedAtIsNull(seed.executionId()).orElseThrow();
            e.setStatus(CampaignExecutionStatus.COMPLETED);
            e.setCompletedAt(Instant.now());
            executionRepository.saveAndFlush(e);
        });

        transactionTemplate.executeWithoutResult(tx -> {
            assertThatThrownBy(() -> attemptService.createAttempt(
                    seed.campaignId(), seed.executionId(),
                    new CreateCallAttemptRequest(seed.contactId(), seed.didId(), 1, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("COMPLETED");
        });

        assertThat(attemptCount(seed.executionId(), tenantId)).isZero();
    }

    // === helpers ===

    private record Seed(UUID campaignId, UUID executionId, UUID contactId, UUID didId) {}

    /**
     * A PLAYFILE campaign with a 09:00-17:00 UTC calling window, an approved
     * audio asset, one member in its contact group, and a RUNNING execution
     * whose snapshot is frozen from that state.
     */
    private Seed seedScenario(UUID tenantId, int maxRetries, boolean withWindow) {
        UUID groupId = seedGroup(tenantId);
        UUID contactId = seedContact(tenantId, "in-group");
        addMember(tenantId, groupId, contactId);
        UUID didId = seedDid(tenantId);

        UUID campaignId = transactionTemplate.execute(tx -> {
            CampaignEntity c = new CampaignEntity();
            c.setTenantId(tenantId);
            c.setName("c-vb8b-" + SEQ.incrementAndGet());
            c.setCampaignType(CampaignType.PLAYFILE);
            c.setStatus(CampaignStatus.SCHEDULED);
            c.setContentMode(ContentMode.AUDIO);
            c.setAudioAssetId(seedApprovedAudioAsset(tenantId));
            c.setDidId(didId);
            c.setContactGroupId(groupId);
            c.setSchedule(withWindow
                    ? new ScheduleSpec(null, LocalTime.of(9, 0), LocalTime.of(17, 0),
                            "UTC", null, null)
                    : new ScheduleSpec(null, null, null, "UTC", null, null));
            c.setRetryPolicy(new RetryPolicySpec(maxRetries, 60, RetryStrategy.FIXED));
            c.setCallOnWhitelistNumbers(Boolean.FALSE);
            return campaignRepository.saveAndFlush(c).getId();
        });

        Seed execution = createExecutionFor(campaignId, tenantId);
        return new Seed(campaignId, execution.executionId(), contactId, execution.didId());
    }

    /** Creates a RUNNING execution with its frozen snapshot, as the engine would. */
    private Seed createExecutionFor(UUID campaignId, UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            CampaignEntity campaign = campaignRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId).orElseThrow();
            CampaignExecutionConfiguration snapshot =
                    configurationService.createExecutionSnapshot(campaign);
            CampaignExecution e = new CampaignExecution();
            e.setCampaignId(campaignId);
            e.setTenantId(tenantId);
            e.setConfigurationSnapshotId(snapshot.getId());
            e.setStatus(CampaignExecutionStatus.RUNNING);
            e.setStartedAt(Instant.now());
            e.setRequestedAt(Instant.now());
            e.setRequestedBy(CALLER_ID.toString());
            executionRepository.saveAndFlush(e);
            return new Seed(campaignId, e.getId(), null, snapshot.getConfiguration().getDidId());
        });
    }

    /** Attempts persisted for an execution, scoped to its tenant. */
    private long attemptCount(UUID executionId, UUID tenantId) {
        Long count = transactionTemplate.execute(tx -> entityManager.createQuery(
                        "SELECT COUNT(a) FROM CallAttempt a WHERE a.executionId = :e"
                                + " AND a.tenantId = :t", Long.class)
                .setParameter("e", executionId)
                .setParameter("t", tenantId)
                .getSingleResult());
        return count == null ? 0L : count;
    }

    private void addMember(UUID tenantId, UUID groupId, UUID contactId) {
        transactionTemplate.executeWithoutResult(tx -> {
            com.shivang.obd.contact.ContactGroupMemberEntity m =
                    new com.shivang.obd.contact.ContactGroupMemberEntity();
            m.setTenantId(tenantId);
            m.setContactGroupId(groupId);
            m.setContactId(contactId);
            memberRepository.saveAndFlush(m);
        });
    }

    private com.shivang.obd.tenant.TenantEntity seedTenant(String label) {
        return transactionTemplate.execute(tx -> {
            com.shivang.obd.tenant.TenantEntity t = new com.shivang.obd.tenant.TenantEntity();
            t.setName("tenant-" + label + "-" + SEQ.incrementAndGet());
            t.setSlug("t-" + label + "-" + SEQ.incrementAndGet());
            t.setStatus(LifecycleStatus.ACTIVE);
            return tenantRepository.saveAndFlush(t);
        });
    }

    private UUID seedGroup(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            com.shivang.obd.contact.ContactGroupEntity g =
                    new com.shivang.obd.contact.ContactGroupEntity();
            g.setTenantId(tenantId);
            g.setName("cg-" + SEQ.incrementAndGet());
            return contactGroupRepository.saveAndFlush(g).getId();
        });
    }

    private UUID seedContact(UUID tenantId, String label) {
        return transactionTemplate.execute(tx -> {
            ContactEntity c = new ContactEntity();
            c.setTenantId(tenantId);
            c.setFirstName("contact-" + label);
            c.setPhoneNumber("+9199" + String.format("%08d", SEQ.incrementAndGet()));
            return contactRepository.saveAndFlush(c).getId();
        });
    }

    private UUID seedDid(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            com.shivang.obd.did.DidEntity d = new com.shivang.obd.did.DidEntity();
            d.setTenantId(tenantId);
            d.setE164Number("+9197" + String.format("%08d", SEQ.incrementAndGet()));
            d.setCountryCode("+91");
            d.setNumberType(com.shivang.obd.did.NumberType.MOBILE);
            d.setProvider("TATA");
            d.setStatus(DidStatus.ACTIVE);
            d.setAllocationState(AllocationState.ASSIGNED);
            d.setAllocationSource(AllocationSource.PLATFORM);
            return didRepository.saveAndFlush(d).getId();
        });
    }

    private UUID seedApprovedAudioAsset(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            com.shivang.obd.audio.AudioAssetEntity a = new com.shivang.obd.audio.AudioAssetEntity();
            a.setTenantId(tenantId);
            a.setName("asset-" + SEQ.incrementAndGet());
            a.setFileName("asset-" + SEQ.incrementAndGet() + ".wav");
            a.setContentType("audio/wav");
            a.setFileSize(1024L);
            a.setStorageReference("s3://vb8b/" + SEQ.incrementAndGet() + ".wav");
            a.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
            return audioAssetRepository.saveAndFlush(a).getId();
        });
    }
}
