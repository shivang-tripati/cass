package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.campaign.CallAttemptStatus;
import com.shivang.obd.contact.ContactRepository;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.media.OutboundDialer;
import com.shivang.obd.voice.media.OutboundDialRequest;
import com.shivang.obd.voice.media.OutboundDialResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-6B.1 — the batch-continuation lock, against real PostgreSQL (full
 * Flyway chain incl. V44/V46): an attempt whose contact is deleted,
 * missing, or foreign must fail ONLY its own attempt
 * ({@code CONTACT_INVALID}, permanent), request no dial, and leave every
 * other valid attempt in the same due batch processed by the scheduler.
 *
 * <p>Harness conventions copied from the known-good PG suites: static
 * container startup, datasource/Flyway routing via
 * {@code @DynamicPropertySource}, {@code JpaAuditConfig} import,
 * {@code NOT_SUPPORTED} propagation. Real repositories over raw-SQL
 * fixtures; only the provider boundary ({@link OutboundDialer}), routing,
 * capacity, and eligibility are mocked. Under the VB-6B.1 identity model
 * the dial-time contact lookup is tenant+live — there is no group-mismatch
 * identity failure (membership was decided at attempt creation).</p>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DialBatchContinuationPostgresIntegrationTest {

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
    private CallAttemptRepository attemptRepository;
    @Autowired
    private ContactRepository contactRepository;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CampaignExecutionRepository executionRepository;
    @Autowired
    private CampaignExecutionConfigurationRepository snapshotRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private CallSessionRepository callSessionRepository;
    @Autowired
    private CallLegRepository callLegRepository;
    // VB-6C.1: real daily-limit ledger over the same PostgreSQL schema.
    @Autowired
    private VoiceBlastDailyUsageRepository dailyUsageRepository;
    @Autowired
    private VoiceBlastDailyUsageEntryRepository dailyUsageEntryRepository;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private OutboundDialService dialService;
    private OutboundDialer dialer;
    private CallEligibility eligibilityService;
    private com.shivang.obd.voice.routing.VoiceRoutingService voiceRoutingService;
    private com.shivang.obd.voice.capacity.VoiceCapacityService voiceCapacity;
    /** VB-6C.1: real ledger over PostgreSQL (admission/confirm/release live). */
    private DailyDialLimitService dailyDialLimitService;
    private DailyAttemptSafetyService dailyAttemptSafetyService;
    @org.springframework.beans.factory.annotation.Autowired
    private VoiceBlastDailyAttemptRepository dailyAttemptRepository;

    private static final String PHONE_A = "+919876500001";
    private static final String PHONE_B = "+919876500002";
    private static final String PHONE_C = "+919876500003";

    private UUID tenantId;
    private UUID campaignId;
    private UUID executionId;
    private UUID groupId;
    private UUID snapshotId;
    private UUID didRowId;
    private UUID gatewayId;

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                    + startupFailure.getMessage(), startupFailure);
        }
        dialer = mock(OutboundDialer.class);
        eligibilityService = mock(CallEligibility.class);
        voiceRoutingService = mock(com.shivang.obd.voice.routing.VoiceRoutingService.class);
        voiceCapacity = mock(com.shivang.obd.voice.capacity.VoiceCapacityService.class);

        dailyDialLimitService = new DailyDialLimitService(
                dailyUsageRepository, dailyUsageEntryRepository,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        // VB-6D.3: the real daily-attempt gate over the real ledger table,
        // so this suite exercises both controls against real PostgreSQL.
        dailyAttemptSafetyService = new DailyAttemptSafetyService(
                dailyAttemptRepository, dailyDialLimitService,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        dialService = new OutboundDialService(
                attemptRepository, contactRepository, tenantRepository,
                campaignRepository, executionRepository,
                new CampaignRuntimeConfigResolver(new CampaignConfigurationService(
                        snapshotRepository,
                        new com.shivang.obd.campaign.config.CampaignTypeConfigValidator())),
                dialer, eligibilityService, voiceRoutingService, voiceCapacity,
                callSessionRepository, callLegRepository,
                // VB-6C.1: real policy service over the real ledger tables.
                dailyDialLimitService, dailyAttemptSafetyService,
                new PreDispatchFailureMapper());
    }

    /** Seeds tenant, campaign, snapshot (group+DID refs), execution; returns group id. */
    private UUID seedExecution() throws Exception {
        tenantId = UUID.randomUUID();
        campaignId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        groupId = UUID.randomUUID();
        snapshotId = UUID.randomUUID();
        didRowId = UUID.randomUUID();
        gatewayId = UUID.randomUUID();

        try (Connection c = raw()) {
            exec(c, "INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?)", ps -> {
                ps.setObject(1, tenantId); ps.setString(2, "t-" + tenantId); ps.setString(3, "s-" + tenantId);
            });
            exec(c, "INSERT INTO campaigns (id, tenant_id, name, campaign_type, status, run_mode) "
                    + "VALUES (?, ?, 'it-dial', 'PLAYFILE', 'SCHEDULED', 'ONE_TIME')", ps -> {
                ps.setObject(1, campaignId); ps.setObject(2, tenantId);
            });
            exec(c, "INSERT INTO contact_groups (id, tenant_id, name) VALUES (?, ?, 'it-group')", ps -> {
                ps.setObject(1, groupId); ps.setObject(2, tenantId);
            });
            // call_sessions.gateway_id FKs to sip_gateways — seed the gateway
            // the routing decision selects. name/FS-name are live-unique.
            exec(c, "INSERT INTO sip_gateways (id, name, provider, free_switch_gateway_name, "
                    + "max_concurrent_channels, enabled) VALUES (?, ?, 'TATA', ?, 10, TRUE)",
                    ps -> {
                ps.setObject(1, gatewayId);
                ps.setString(2, "gw-" + gatewayId);
                ps.setString(3, "fs-" + gatewayId);
            });
            // DID E.164 is live-unique across the schema — randomize it.
            String didE164 = "+9198" + String.format("%08d",
                    Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
            exec(c, "INSERT INTO dids (id, tenant_id, e164_number, country_code, number_type, "
                    + "provider, status, allocation_state) VALUES (?, ?, ?, '+91', "
                    + "'MOBILE', 'TATA', 'ACTIVE', 'ASSIGNED')", ps -> {
                ps.setObject(1, didRowId); ps.setObject(2, tenantId); ps.setString(3, didE164);
            });
            exec(c, "INSERT INTO campaign_execution_configurations (id, campaign_id, tenant_id, "
                    + "campaign_type, contact_group_id, did_id, retry_max_attempts, retry_strategy, "
                    + "call_on_whitelist_numbers, timezone) VALUES (?, ?, ?, 'PLAYFILE', ?, ?, 0, 'FIXED', FALSE, 'UTC')",
                    ps -> {
                ps.setObject(1, snapshotId); ps.setObject(2, campaignId);
                ps.setObject(3, tenantId); ps.setObject(4, groupId); ps.setObject(5, didRowId);
            });
            exec(c, "INSERT INTO campaign_executions (id, campaign_id, tenant_id, status, "
                    + "requested_by, configuration_snapshot_id) VALUES (?, ?, ?, 'RUNNING', 'it', ?)",
                    ps -> {
                ps.setObject(1, executionId); ps.setObject(2, campaignId);
                ps.setObject(3, tenantId); ps.setObject(4, snapshotId);
            });
        }
        return groupId;
    }

    private UUID seedContact(UUID ownerTenant, String phone) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = raw()) {
            exec(c, "INSERT INTO contacts (id, tenant_id, phone_number) VALUES (?, ?, ?)", ps -> {
                ps.setObject(1, id); ps.setObject(2, ownerTenant); ps.setString(3, phone);
            });
        }
        return id;
    }

    private UUID seedAttempt(UUID contactId) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = raw()) {
            exec(c, "INSERT INTO call_attempts (id, execution_id, campaign_id, tenant_id, "
                    + "contact_id, did_id, attempt_number, status, scheduled_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 1, 'QUEUED', now() - interval '1 minute')", ps -> {
                ps.setObject(1, id); ps.setObject(2, executionId);
                ps.setObject(3, campaignId); ps.setObject(4, tenantId);
                ps.setObject(5, contactId); ps.setObject(6, didRowId);
            });
        }
        return id;
    }

    private void softDeleteContact(UUID contactId) throws Exception {
        try (Connection c = raw()) {
            exec(c, "UPDATE contacts SET deleted_at = now() WHERE id = ?", ps ->
                ps.setObject(1, contactId));
        }
    }

    /**
     * All attempts in these tests pass eligibility and routing; capacity is
     * available. Stubs are destination-agnostic — WHICH numbers reached the
     * provider is proven by the dialer argument assertions in each test.
     */
    private void stubEligibilityRoutingCapacity() {
        when(eligibilityService.evaluate(any(CallEligibility.Context.class), anyString()))
                .thenReturn(CallEligibility.EligibilityResult.allowed());
        when(voiceRoutingService.resolveRoute(any(), any(), anyString(), eq(didRowId), anyString(), any()))
                .thenAnswer(inv -> com.shivang.obd.voice.routing.VoiceRoutingDecision.primary(
                        new com.shivang.obd.voice.routing.VoiceRoute(
                                gatewayId, "fs-" + gatewayId, "external", "TATA", didRowId,
                                inv.getArgument(2)),
                        com.shivang.obd.voice.routing.VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode(),
                        List.of()));
        when(voiceCapacity.reserve(eq(gatewayId), any(UUID.class))).thenReturn(true);
        when(dialer.dial(any(OutboundDialRequest.class)))
                .thenReturn(OutboundDialResponse.accepted("fs-accepted"));
    }

    private static Connection raw() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private interface SqlBinder {
        void bind(PreparedStatement ps) throws Exception;
    }

    private static void exec(Connection c, String sql, SqlBinder binder) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("IT-B1: deleted contact among valid ones — only its attempt fails, batch continues")
    void deletedContactFailsAloneAndBatchContinues() throws Exception {
        seedExecution();
        UUID deletedContact = seedContact(tenantId, PHONE_A);
        UUID validB = seedContact(tenantId, PHONE_B);
        UUID validC = seedContact(tenantId, PHONE_C);
        UUID attemptA = seedAttempt(deletedContact);
        UUID attemptB = seedAttempt(validB);
        UUID attemptC = seedAttempt(validC);
        softDeleteContact(deletedContact);

        stubEligibilityRoutingCapacity();

        // The dial path now performs @Modifying ledger writes (VB-6C.1);
        // the constructed (non-proxied) service needs an active transaction.
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(t -> dialService.processDueAttempts());

        var a = attemptRepository.findById(attemptA).orElseThrow();
        var b = attemptRepository.findById(attemptB).orElseThrow();
        var c = attemptRepository.findById(attemptC).orElseThrow();

        assertThat(a.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(a.getFailureCode()).isEqualTo(CallFailureCode.CONTACT_INVALID.name());
        assertThat(b.getStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);
        assertThat(b.getProviderCallId()).isEqualTo("fs-accepted");
        assertThat(c.getStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);

        // The provider dial ran exactly twice — for the two VALID contacts —
        // and never for the deleted contact's number.
        verify(dialer, times(2)).dial(any(OutboundDialRequest.class));
        verify(dialer, times(1)).dial(argThat((OutboundDialRequest r) ->
                PHONE_B.equals(r.destinationNumber())));
        verify(dialer, times(1)).dial(argThat((OutboundDialRequest r) ->
                PHONE_C.equals(r.destinationNumber())));
        verify(dialer, times(0)).dial(argThat((OutboundDialRequest r) ->
                PHONE_A.equals(r.destinationNumber())));
    }

    @Test
    @DisplayName("IT-B2: missing contact (dangling reference) — attempt fails alone, batch continues")
    void missingContactFailsAloneAndBatchContinues() throws Exception {
        seedExecution();
        UUID validB = seedContact(tenantId, PHONE_B);
        UUID attemptA = seedAttempt(UUID.randomUUID()); // no contact row at all
        UUID attemptB = seedAttempt(validB);

        stubEligibilityRoutingCapacity();

        // The dial path now performs @Modifying ledger writes (VB-6C.1);
        // the constructed (non-proxied) service needs an active transaction.
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(t -> dialService.processDueAttempts());

        var a = attemptRepository.findById(attemptA).orElseThrow();
        var b = attemptRepository.findById(attemptB).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(a.getFailureCode()).isEqualTo(CallFailureCode.CONTACT_INVALID.name());
        assertThat(b.getStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);
        verify(dialer, times(1)).dial(any(OutboundDialRequest.class));
    }

    @Test
    @DisplayName("IT-B3: foreign-tenant contact — fails closed without leaking existence, batch continues")
    void foreignTenantContactFailsClosed() throws Exception {
        seedExecution();
        UUID foreignTenant = UUID.randomUUID();
        try (Connection c = raw()) {
            exec(c, "INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?)", ps -> {
                ps.setObject(1, foreignTenant); ps.setString(2, "t-f"); ps.setString(3, "s-f");
            });
        }
        UUID foreignContact = seedContact(foreignTenant, PHONE_A);
        UUID validB = seedContact(tenantId, PHONE_B);
        UUID attemptA = seedAttempt(foreignContact);
        UUID attemptB = seedAttempt(validB);

        stubEligibilityRoutingCapacity();

        // The dial path now performs @Modifying ledger writes (VB-6C.1);
        // the constructed (non-proxied) service needs an active transaction.
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(t -> dialService.processDueAttempts());

        var a = attemptRepository.findById(attemptA).orElseThrow();
        var b = attemptRepository.findById(attemptB).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(a.getFailureCode()).isEqualTo(CallFailureCode.CONTACT_INVALID.name());
        assertThat(b.getStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);
        verify(dialer, times(1)).dial(any(OutboundDialRequest.class));
    }

    @Test
    @DisplayName("IT-B4: another execution's valid attempt is unaffected by a bad batch-mate")
    void independentTenantBatchesUnaffected() throws Exception {
        seedExecution();
        UUID deletedContact = seedContact(tenantId, PHONE_A);
        UUID attemptA = seedAttempt(deletedContact);
        softDeleteContact(deletedContact);

        // A second execution of the same tenant with a valid contact
        // (properly seeded: attempt.execution_id FKs to campaign_executions).
        UUID execution2 = UUID.randomUUID();
        UUID snapshot2 = UUID.randomUUID();
        try (Connection c = raw()) {
            exec(c, "INSERT INTO campaign_execution_configurations (id, campaign_id, tenant_id, "
                    + "campaign_type, contact_group_id, did_id, retry_max_attempts, retry_strategy, "
                    + "call_on_whitelist_numbers, timezone) VALUES (?, ?, ?, 'PLAYFILE', ?, ?, 0, 'FIXED', FALSE, 'UTC')",
                    ps -> {
                ps.setObject(1, snapshot2); ps.setObject(2, campaignId);
                ps.setObject(3, tenantId); ps.setObject(4, groupId); ps.setObject(5, didRowId);
            });
            exec(c, "INSERT INTO campaign_executions (id, campaign_id, tenant_id, status, "
                    + "requested_by, configuration_snapshot_id) VALUES (?, ?, ?, 'RUNNING', 'it', ?)",
                    ps -> {
                ps.setObject(1, execution2); ps.setObject(2, campaignId);
                ps.setObject(3, tenantId); ps.setObject(4, snapshot2);
            });
        }
        UUID validB = seedContact(tenantId, PHONE_B);
        UUID attemptB = seedAttempt(validB);
        try (Connection c = raw()) {
            exec(c, "UPDATE call_attempts SET execution_id = ? WHERE id = ?", ps -> {
                ps.setObject(1, execution2); ps.setObject(2, attemptB);
            });
        }

        stubEligibilityRoutingCapacity();

        // The dial path now performs @Modifying ledger writes (VB-6C.1);
        // the constructed (non-proxied) service needs an active transaction.
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(t -> dialService.processDueAttempts());

        var a = attemptRepository.findById(attemptA).orElseThrow();
        var b = attemptRepository.findById(attemptB).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(a.getFailureCode()).isEqualTo(CallFailureCode.CONTACT_INVALID.name());
        assertThat(b.getStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);
        verify(dialer, times(1)).dial(any(OutboundDialRequest.class));
    }
}
