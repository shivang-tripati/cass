package com.shivang.obd.voice.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.campaign.CallAttemptStatus;
import com.shivang.obd.campaign.CampaignRepository;
import com.shivang.obd.campaign.DtmfExecutionService;
import com.shivang.obd.telephony.EslEvent;
import com.shivang.obd.telephony.EslEventService;
import com.shivang.obd.telephony.SipGatewayAllocationRepository;
import com.shivang.obd.telephony.SipGatewayRepository;
import com.shivang.obd.telephony.VoiceCapacityServiceImpl;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.dtmf.DtmfInteraction;
import com.shivang.obd.voice.dtmf.DtmfInteractionRepository;
import com.shivang.obd.voice.dtmf.DtmfResultService;
import com.shivang.obd.voice.dtmf.DtmfResultType;
import com.shivang.obd.voice.media.DtmfCollectorTrigger;
import com.shivang.obd.voice.media.VoiceMediaController;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-2 integration tests against real PostgreSQL (full Flyway chain V1..V35):
 * DTMF lifecycle (attempt → session → leg → reservation → interaction →
 * terminal → reservation released) driven through the REAL
 * {@link EslEventService}, REAL {@link DtmfExecutionService} and REAL
 * {@link DtmfInteractionRepository} — proving the NAMED_ENUM mapping for the
 * native {@code dtmf_result_type} column end to end. Only the FreeSWITCH ESL
 * boundary ({@link VoiceMediaController}) is mocked, per the established
 * test contract.
 */
class DtmfLifecycleIntegrationTest extends VoicePostgresIntegrationSupport {

    private static final String AUDIO_REF = "tenants/it/it-dtmf.wav";

    @Autowired
    private CallAttemptRepository attemptRepository;
    @Autowired
    private CallSessionRepository callSessionRepository;
    @Autowired
    private CallLegRepository callLegRepository;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private com.shivang.obd.campaign.CampaignExecutionRepository executionRepository;
    @Autowired
    private com.shivang.obd.campaign.CampaignExecutionConfigurationRepository configVersionRepository;
    @Autowired
    private AudioAssetRepository audioAssetRepository;
    @Autowired
    private DtmfInteractionRepository interactionRepository;
    @Autowired
    private SipGatewayRepository gatewayRepository;
    @Autowired
    private SipGatewayAllocationRepository allocationRepository;
    @PersistenceContext
    private EntityManager entityManager;

    private VoiceMediaController mediaController;
    private EslEventService eslEventService;
    private DtmfExecutionService dtmfExecutionService;

    @BeforeEach
    void wireServices() {
        mediaController = mock(VoiceMediaController.class);

        VoiceCapacityServiceImpl capacity =
                new VoiceCapacityServiceImpl(gatewayRepository, allocationRepository);
        ReflectionTestUtils.setField(capacity, "entityManager", entityManager);

        DtmfResultService resultService = new DtmfResultService(interactionRepository);
        dtmfExecutionService = new DtmfExecutionService(
                callSessionRepository, callLegRepository, attemptRepository,
                campaignRepository, executionRepository, audioAssetRepository,
                // Real canonical validator (VB-5E) over the autowired audio repo.
                new com.shivang.obd.campaign.CampaignResourceValidationService(
                        null, audioAssetRepository, null),
                new com.shivang.obd.campaign.CampaignRuntimeConfigResolver(
                        new com.shivang.obd.campaign.CampaignConfigurationService(
                                configVersionRepository,
                                new com.shivang.obd.campaign.config.CampaignTypeConfigValidator())),
                interactionRepository, resultService, mediaController,
                new org.springframework.beans.factory.ObjectProvider<com.shivang.obd.voice.agent.AgentConnectTrigger>() {
                    @Override
                    public com.shivang.obd.voice.agent.AgentConnectTrigger getObject(Object... args) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public com.shivang.obd.voice.agent.AgentConnectTrigger getObject() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public com.shivang.obd.voice.agent.AgentConnectTrigger getIfAvailable() {
                        return null; // agent-less DTMF regression context
                    }

                    @Override
                    public com.shivang.obd.voice.agent.AgentConnectTrigger getIfUnique() {
                        return null;
                    }
                });

        eslEventService = new EslEventService(
                attemptRepository, callSessionRepository, callLegRepository,
                capacity, mediaController,
                java.util.List.of(dtmfExecutionService),
                Optional.<DtmfCollectorTrigger>of(dtmfExecutionService),
                resultService,
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
    }

    private UUID tenantId;
    private UUID gatewayId;
    private UUID campaignId;
    private UUID executionId;
    private UUID sessionId;

    /**
     * Seeds a DTMF campaign (with the given type_config) plus the full call
     * fixture chain; returns the provider call UUID used for ESL events.
     */
    private String seedDtmfCall(String typeConfigJson) throws SQLException {
        tenantId = UUID.randomUUID();
        gatewayId = UUID.randomUUID();
        campaignId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        UUID legId = UUID.randomUUID();
        String callUuid = "it-dtmf-" + attemptId;

        try (Connection c = rawConnection()) {
            VoicePostgresIntegrationSupport.insertTenantRow(c, tenantId);
            VoicePostgresIntegrationSupport.insertGatewayRow(c, gatewayId, 10);
            insertAudioAsset(c, assetId, tenantId);
            insertDtmfCampaign(c, campaignId, assetId, typeConfigJson);
            insertExecution(c, executionId, assetId, typeConfigJson);
            insertAttempt(c, attemptId, callUuid);
            insertSession(c, sessionId, attemptId, callUuid);
            insertLeg(c, legId, sessionId, callUuid);
            insertReservation(c, gatewayId, tenantId);
        }
        return callUuid;
    }

    private void insertAudioAsset(Connection c, UUID id, UUID ownerTenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audio_assets (id, tenant_id, name, file_name, content_type, "
                        + "file_size, storage_reference, status) "
                        + "VALUES (?, ?, 'it-asset', 'prompt.wav', 'audio/wav', 1024, ?, 'APPROVED')")) {
            ps.setObject(1, id);
            ps.setObject(2, ownerTenantId);
            ps.setString(3, AUDIO_REF);
            ps.executeUpdate();
        }
    }

    private void insertDtmfCampaign(Connection c, UUID id, UUID audioAssetId, String typeConfigJson)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO campaigns (id, tenant_id, name, campaign_type, status, "
                        + "content_mode, audio_asset_id, type_config) "
                        + "VALUES (?, ?, 'it-dtmf-campaign', 'DTMF', 'RUNNING', 'AUDIO', ?, ?::jsonb)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, audioAssetId);
            ps.setString(4, typeConfigJson);
            ps.executeUpdate();
        }
    }

    /**
     * VB-6A correction: executions must own an immutable configuration
     * snapshot (NOT NULL FK), so the harness seeds one mirroring the
     * campaign's DTMF configuration.
     */
    private void insertExecution(Connection c, UUID id, UUID audioAssetId, String typeConfigJson)
            throws SQLException {
        UUID snapshotId = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO campaign_execution_configurations (id, campaign_id, tenant_id, "
                        + "campaign_type, content_mode, audio_asset_id, retry_max_attempts, "
                        + "retry_strategy, type_config, call_on_whitelist_numbers) "
                        + "VALUES (?, ?, ?, 'DTMF', 'AUDIO', ?, 0, 'FIXED', ?::jsonb, FALSE)")) {
            ps.setObject(1, snapshotId);
            ps.setObject(2, campaignId);
            ps.setObject(3, tenantId);
            ps.setObject(4, audioAssetId);
            ps.setString(5, typeConfigJson);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO campaign_executions (id, campaign_id, tenant_id, status, requested_by, "
                        + "configuration_snapshot_id) "
                        + "VALUES (?, ?, ?, 'RUNNING', 'it-test', ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, campaignId);
            ps.setObject(3, tenantId);
            ps.setObject(4, snapshotId);
            ps.executeUpdate();
        }
    }

    private void insertAttempt(Connection c, UUID id, String callUuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO call_attempts (id, execution_id, campaign_id, tenant_id, "
                        + "contact_id, did_id, attempt_number, status, provider_call_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 'QUEUED', ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, executionId);
            ps.setObject(3, campaignId);
            ps.setObject(4, tenantId);
            ps.setObject(5, UUID.randomUUID());
            ps.setObject(6, UUID.randomUUID());
            ps.setString(7, callUuid);
            ps.executeUpdate();
        }
    }

    private void insertSession(Connection c, UUID id, UUID attemptId, String callUuid)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO call_sessions (id, tenant_id, direction, call_type, status, "
                        + "destination_number, provider_call_id, call_attempt_id, "
                        + "campaign_execution_id, gateway_id) "
                        + "VALUES (?, ?, 'OUTBOUND', 'VOICE_BLAST', 'DIALING', '+919876543210', "
                        + "?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setString(3, callUuid);
            ps.setObject(4, attemptId);
            ps.setObject(5, executionId);
            ps.setObject(6, gatewayId);
            ps.executeUpdate();
        }
    }

    private void insertLeg(Connection c, UUID id, UUID sessionId, String callUuid)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO call_legs (id, call_session_id, leg_type, direction, status, "
                        + "target, provider_call_id) "
                        + "VALUES (?, ?, 'CUSTOMER', 'OUTBOUND', 'DIALING', '+919876543210', ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, sessionId);
            ps.setString(3, callUuid);
            ps.executeUpdate();
        }
    }

    private void insertReservation(Connection c, UUID gateway, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO voice_channel_reservations (gateway_id, tenant_id) VALUES (?, ?)")) {
            ps.setObject(1, gateway);
            ps.setObject(2, tenant);
            ps.executeUpdate();
        }
    }

    private int activeReservations(UUID gateway) {
        Number count = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM voice_channel_reservations "
                        + "WHERE gateway_id = ? AND released_at IS NULL")
                .setParameter(1, gateway)
                .getSingleResult();
        return count.intValue();
    }

    private static EslEvent event(String name, String callUuid, String... kv) {
        EslEvent e = new EslEvent(name);
        e.addHeader("Call-UUID", callUuid);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            e.addHeader(kv[i], kv[i + 1]);
        }
        return e;
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("IT-D1: full DTMF lifecycle — valid digit, single hangup, reservation released")
    void fullDtmfLifecycleValidDigit() throws Exception {
        String callUuid = seedDtmfCall(
                "{\"dtmf\": {\"expected\": \"1\", \"timeoutSecs\": 10}}");

        eslEventService.processEvent(event("CHANNEL_ANSWER", callUuid));
        verify(mediaController).playAudio(any(UUID.class), any(), eq(AUDIO_REF));

        eslEventService.processEvent(event("PLAYBACK_START", callUuid));
        eslEventService.processEvent(event("PLAYBACK_STOP", callUuid));

        // Playback completion created the persisted interaction (real JPA save
        // against the native dtmf_result_type enum — NAMED_ENUM proof) and
        // moved the session to WAITING_FOR_DTMF.
        DtmfInteraction interaction = interactionRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId).orElseThrow();
        assertThat(interaction.getResult()).isEqualTo(DtmfResultType.COLLECTING);
        assertThat(interaction.getExpectedInput()).isEqualTo("1");
        assertThat(callSessionRepository.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(CallSessionStatus.WAITING_FOR_DTMF);

        // Digits before collection are ignored (arrive during playback).
        // Here the collection is active: expected digit → VALID + hangup.
        eslEventService.processEvent(event("CHANNEL_DTMF", callUuid, "DTMF-Digit", "1"));
        verify(mediaController, times(1)).terminateCall(eq(sessionId), isNull());

        // Duplicate digit after the terminal result: no second action.
        eslEventService.processEvent(event("CHANNEL_DTMF", callUuid, "DTMF-Digit", "1"));
        verify(mediaController, times(1)).terminateCall(eq(sessionId), isNull());

        eslEventService.processEvent(
                event("CHANNEL_HANGUP", callUuid, "Hangup-Cause", "NORMAL_CLEARING"));
        // Duplicate hangup: reservation state unchanged.
        eslEventService.processEvent(
                event("CHANNEL_HANGUP", callUuid, "Hangup-Cause", "NORMAL_CLEARING"));

        var attempt = attemptRepository.findByProviderCallIdAndDeletedAtIsNull(callUuid).orElseThrow();
        var session = callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(callUuid).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(session.getStatus()).isEqualTo(CallSessionStatus.COMPLETED);
        assertThat(activeReservations(gatewayId)).isZero();

        DtmfInteraction finalized = interactionRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId).orElseThrow();
        assertThat(finalized.getResult()).isEqualTo(DtmfResultType.VALID);
        assertThat(finalized.getCollectedDigits()).isEqualTo("1");
        assertThat(finalized.getResultAt()).isNotNull();
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("IT-D2: DTMF timeout — TIMEOUT result persisted, attempt completes normally, reservation released")
    void dtmfTimeoutPersistsAndReleases() throws Exception {
        String callUuid = seedDtmfCall(
                "{\"dtmf\": {\"expected\": \"123\", \"timeoutSecs\": 10}}");

        eslEventService.processEvent(event("CHANNEL_ANSWER", callUuid));
        eslEventService.processEvent(event("PLAYBACK_START", callUuid));
        eslEventService.processEvent(event("PLAYBACK_STOP", callUuid));

        // Partial input before the window expires.
        eslEventService.processEvent(event("CHANNEL_DTMF", callUuid, "DTMF-Digit", "1"));

        // Timeout enforcement (poller driver — the poller itself only selects
        // expired rows and delegates here; claim makes the finalize atomic).
        dtmfExecutionService.onDtmfTimeout(sessionId);
        verify(mediaController).terminateCall(eq(sessionId), isNull());

        eslEventService.processEvent(
                event("CHANNEL_HANGUP", callUuid, "Hangup-Cause", "NORMAL_CLEARING"));

        var attempt = attemptRepository.findByProviderCallIdAndDeletedAtIsNull(callUuid).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(activeReservations(gatewayId)).isZero();

        DtmfInteraction finalized = interactionRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId).orElseThrow();
        assertThat(finalized.getResult()).isEqualTo(DtmfResultType.TIMEOUT);
        assertThat(finalized.getCollectedDigits()).isEqualTo("1");
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("IT-D3: invalid DTMF typeConfig — permanent DTMF_CONFIG_INVALID, no interaction row")
    void invalidConfigPermanentFailure() throws Exception {
        String callUuid = seedDtmfCall(
                "{\"dtmf\": {\"expected\": \"abc\"}}");

        eslEventService.processEvent(event("CHANNEL_ANSWER", callUuid));
        eslEventService.processEvent(event("PLAYBACK_START", callUuid));
        eslEventService.processEvent(event("PLAYBACK_STOP", callUuid));

        // Config failure tears the call down through the media boundary; no
        // interaction row is ever created.
        verify(mediaController).terminateCall(any(UUID.class), eq(callUuid));
        assertThat(interactionRepository.findByCallSessionIdAndDeletedAtIsNull(sessionId))
                .isEmpty();

        eslEventService.processEvent(
                event("CHANNEL_HANGUP", callUuid, "Hangup-Cause", "NORMAL_CLEARING"));

        var attempt = attemptRepository.findByProviderCallIdAndDeletedAtIsNull(callUuid).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(attempt.getFailureCode()).isEqualTo(DtmfExecutionService.DTMF_CONFIG_INVALID_CODE);
        assertThat(activeReservations(gatewayId)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("IT-D4: V35 schema — dtmf_result_type enum values, WAITING_FOR_DTMF state, table structure")
    void migrationAndEnumSchema() throws Exception {
        try (Connection c = rawConnection()) {
            String enumValues = scalarString(c,
                    "SELECT string_agg(val::text, ',' ORDER BY val) "
                            + "FROM unnest(enum_range(NULL::dtmf_result_type)) AS val");
            assertThat(enumValues).isEqualTo("COLLECTING,VALID,INVALID,TIMEOUT,ABANDONED");

            String statusValues = scalarString(c,
                    "SELECT string_agg(val::text, ',' ORDER BY val) "
                            + "FROM unnest(enum_range(NULL::call_session_status)) AS val");
            assertThat(statusValues).contains("WAITING_FOR_DTMF").contains("PLAYBACK_COMPLETED");

            assertThat(VoicePostgresIntegrationSupport.exists(c,
                    "SELECT 1 FROM information_schema.tables "
                            + "WHERE table_name = 'dtmf_interactions'")).isTrue();
            assertThat(VoicePostgresIntegrationSupport.exists(c,
                    "SELECT 1 FROM information_schema.columns "
                            + "WHERE table_name = 'dtmf_interactions' AND column_name = 'result' "
                            + "AND data_type = 'USER-DEFINED'")).isTrue();
            // FKs: tenant + call session.
            assertThat(VoicePostgresIntegrationSupport.exists(c,
                    "SELECT 1 FROM information_schema.table_constraints "
                            + "WHERE table_name = 'dtmf_interactions' "
                            + "AND constraint_type = 'FOREIGN KEY'")).isTrue();
        }
    }

    private static String scalarString(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
