package com.shivang.obd.voice.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.campaign.CampaignRepository;
import com.shivang.obd.campaign.PlayfileExecutionService;
import com.shivang.obd.telephony.EslEvent;
import com.shivang.obd.telephony.EslEventService;
import com.shivang.obd.telephony.SipGatewayAllocationRepository;
import com.shivang.obd.telephony.SipGatewayRepository;
import com.shivang.obd.telephony.VoiceCapacityServiceImpl;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.media.PlaybackTrigger;
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
 * VB-1 integration test against real PostgreSQL (full Flyway chain V1..V34):
 * campaign attempt → call session → call leg → reservation → terminal state →
 * reservation released, driven through the REAL {@link EslEventService} and
 * REAL {@link PlayfileExecutionService}. Only the FreeSWITCH ESL boundary
 * ({@link VoiceMediaController}) is mocked, per the VB-1 test contract.
 * <p>
 * The container database is shared across tests, so every test seeds its own
 * rows with fresh UUIDs and asserts only against them.
 */
class PlayfileLifecycleIntegrationTest extends VoicePostgresIntegrationSupport {

    private static final String AUDIO_REF = "tenants/it/it-promo.wav";

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
    private SipGatewayRepository gatewayRepository;
    @Autowired
    private SipGatewayAllocationRepository allocationRepository;
    @PersistenceContext
    private EntityManager entityManager;

    private VoiceMediaController mediaController;
    private EslEventService eslEventService;

    @BeforeEach
    void wireServices() {
        mediaController = mock(VoiceMediaController.class);

        VoiceCapacityServiceImpl capacity =
                new VoiceCapacityServiceImpl(gatewayRepository, allocationRepository);
        ReflectionTestUtils.setField(capacity, "entityManager", entityManager);

        PlayfileExecutionService playback = new PlayfileExecutionService(
                callSessionRepository, callLegRepository, attemptRepository,
                campaignRepository, executionRepository, audioAssetRepository,
                // Real canonical validator (VB-5E) over the autowired audio repo.
                new com.shivang.obd.campaign.CampaignResourceValidationService(
                        null, audioAssetRepository, null),
                new com.shivang.obd.campaign.CampaignRuntimeConfigResolver(
                        new com.shivang.obd.campaign.CampaignConfigurationService(
                                configVersionRepository,
                                new com.shivang.obd.campaign.config.CampaignTypeConfigValidator())),
                mediaController);

        eslEventService = new EslEventService(
                attemptRepository, callSessionRepository, callLegRepository,
                capacity, mediaController,
                java.util.List.<PlaybackTrigger>of(playback),
                Optional.empty(),
                new com.shivang.obd.voice.dtmf.DtmfResultService(
                        org.mockito.Mockito.mock(com.shivang.obd.voice.dtmf.DtmfInteractionRepository.class)),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
    }

    /** Per-test fixture ids — the container database is shared. */
    private UUID tenantId;
    private UUID gatewayId;
    private UUID campaignId;
    private UUID executionId;
    private UUID assetId;

    /** Seeds all fixture rows for one call on the given connection. */
    private String seedCall(UUID assetIdForCampaign) throws SQLException {
        tenantId = UUID.randomUUID();
        gatewayId = UUID.randomUUID();
        campaignId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        assetId = assetIdForCampaign != null ? assetIdForCampaign : UUID.randomUUID();

        UUID foreignTenantId = UUID.randomUUID();
        UUID assetAId = UUID.randomUUID();
        UUID assetBId = UUID.randomUUID();
        // null → the campaign references the tenant-owned asset (happy path);
        // a concrete id → the campaign references that (possibly foreign) asset.
        UUID campaignAssetId = assetIdForCampaign != null ? assetIdForCampaign : assetAId;
        UUID attemptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID legId = UUID.randomUUID();
        String callUuid = "it-uuid-" + attemptId;

        try (Connection c = rawConnection()) {
            VoicePostgresIntegrationSupport.insertTenantRow(c, tenantId);
            VoicePostgresIntegrationSupport.insertTenantRow(c, foreignTenantId);
            VoicePostgresIntegrationSupport.insertGatewayRow(c, gatewayId, 10);

            insertAudioAsset(c, assetAId, tenantId);
            insertAudioAsset(c, assetBId, foreignTenantId); // other tenant's asset
            insertCampaign(c, campaignId, campaignAssetId);
            insertExecution(c, executionId, campaignAssetId);

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
                        + "VALUES (?, ?, 'it-asset', 'promo.wav', 'audio/wav', 1024, ?, 'APPROVED')")) {
            ps.setObject(1, id);
            ps.setObject(2, ownerTenantId);
            ps.setString(3, AUDIO_REF);
            ps.executeUpdate();
        }
    }

    private void insertCampaign(Connection c, UUID id, UUID audioAssetId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO campaigns (id, tenant_id, name, campaign_type, status, "
                        + "content_mode, audio_asset_id) "
                        + "VALUES (?, ?, 'it-campaign', 'PLAYFILE', 'RUNNING', 'AUDIO', ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, audioAssetId);
            ps.executeUpdate();
        }
    }

    /**
     * VB-6A correction: executions must own an immutable configuration
     * snapshot (NOT NULL FK), so the harness seeds one mirroring the
     * campaign's execution-affecting columns.
     */
    private void insertExecution(Connection c, UUID id, UUID audioAssetId) throws SQLException {
        UUID snapshotId = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO campaign_execution_configurations (id, campaign_id, tenant_id, "
                        + "campaign_type, content_mode, audio_asset_id, retry_max_attempts, "
                        + "retry_strategy, call_on_whitelist_numbers) "
                        + "VALUES (?, ?, ?, 'PLAYFILE', 'AUDIO', ?, 0, 'FIXED', FALSE)")) {
            ps.setObject(1, snapshotId);
            ps.setObject(2, campaignId);
            ps.setObject(3, tenantId);
            ps.setObject(4, audioAssetId);
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

    /**
     * Active-reservation count, read through the test's own transaction — the
     * release UPDATE runs inside that same transaction, so a separate raw
     * connection would not see it (READ COMMITTED snapshot isolation).
     */
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
    @DisplayName("IT-1: full PLAYFILE lifecycle ends COMPLETED with reservation released once")
    void fullPlayfileLifecycleReleasesReservationOnce() throws Exception {
        String callUuid = seedCall(null);

        eslEventService.processEvent(event("CHANNEL_PROGRESS", callUuid));
        eslEventService.processEvent(event("CHANNEL_ANSWER", callUuid));
        // The real PlayfileExecutionService resolved the PLAYFILE campaign and
        // the tenant-owned APPROVED asset from the database, then requested
        // playback through the media boundary.
        verify(mediaController).playAudio(any(UUID.class), any(), eq(AUDIO_REF));

        eslEventService.processEvent(event("PLAYBACK_START", callUuid));
        eslEventService.processEvent(event("PLAYBACK_STOP", callUuid));
        verify(mediaController).terminateCall(any(UUID.class), eq(callUuid));

        eslEventService.processEvent(
                event("CHANNEL_HANGUP", callUuid, "Hangup-Cause", "NORMAL_CLEARING"));

        var attempt = attemptRepository.findByProviderCallIdAndDeletedAtIsNull(callUuid).orElseThrow();
        var session = callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(callUuid).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(com.shivang.obd.campaign.CallAttemptStatus.COMPLETED);
        assertThat(session.getStatus())
                .isEqualTo(com.shivang.obd.voice.call.CallSessionStatus.COMPLETED);
        assertThat(activeReservations(gatewayId)).isZero();

        // Duplicate hangup: ignored, reservation state unchanged.
        eslEventService.processEvent(
                event("CHANNEL_HANGUP", callUuid, "Hangup-Cause", "NORMAL_CLEARING"));
        assertThat(activeReservations(gatewayId)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("IT-2: playback failure path fails the attempt and still releases the reservation")
    void playbackFailureReleasesReservation() throws Exception {
        String callUuid = seedCall(null);

        eslEventService.processEvent(event("CHANNEL_ANSWER", callUuid));
        eslEventService.processEvent(event("PLAYBACK_START", callUuid));
        eslEventService.processEvent(
                event("PLAYBACK_ERROR", callUuid, "Playback-Error", "RESOURCE_ERROR"));
        eslEventService.processEvent(
                event("CHANNEL_HANGUP", callUuid, "Hangup-Cause", "NORMAL_CLEARING"));

        var attempt = attemptRepository.findByProviderCallIdAndDeletedAtIsNull(callUuid).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(com.shivang.obd.campaign.CallAttemptStatus.FAILED);
        assertThat(attempt.getFailureCode()).isEqualTo("PLAYBACK_FAILED");
        assertThat(activeReservations(gatewayId)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("IT-3: cross-tenant audio asset is refused — no playback, config-invalid outcome")
    void crossTenantAssetRefused() throws Exception {
        // The campaign references an asset owned by another tenant: the
        // tenant-scoped asset finder must not resolve it.
        String callUuid = seedCall(UUID.randomUUID()); // fresh id owned by nobody

        eslEventService.processEvent(event("CHANNEL_ANSWER", callUuid));

        verify(mediaController, never()).playAudio(any(UUID.class), any(), anyString());
        // Config-invalid teardown requested through the media boundary.
        verify(mediaController).terminateCall(any(UUID.class), eq(callUuid));

        eslEventService.processEvent(
                event("CHANNEL_HANGUP", callUuid, "Hangup-Cause", "NORMAL_CLEARING"));

        var attempt = attemptRepository.findByProviderCallIdAndDeletedAtIsNull(callUuid).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(com.shivang.obd.campaign.CallAttemptStatus.FAILED);
        assertThat(attempt.getFailureCode()).isEqualTo("PLAYBACK_CONFIG_INVALID");
        assertThat(activeReservations(gatewayId)).isZero();
    }
}
