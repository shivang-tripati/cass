package com.shivang.obd.campaign;

import static com.shivang.obd.voice.VoiceTestSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.audio.AudioAssetEntity;
import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.audio.AudioAssetStatus;
import com.shivang.obd.campaign.CallAttemptStatus;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VB-1 campaign execution tests (P1â€“P3, P24): the PlaybackTrigger only plays
 * media for PLAYFILE+AUDIO campaigns with a valid, APPROVED, tenant-owned
 * audio asset; configuration problems are classified as permanent
 * PLAYBACK_CONFIG_INVALID and command failures as temporary PLAYBACK_FAILED.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlayfileExecutionServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B =
            UUID.fromString("bb000000-0000-4000-8000-00000000000b");
    private static final UUID CAMPAIGN_ID =
            UUID.fromString("cd000000-0000-4000-8000-0000000000c1");
    private static final UUID EXECUTION_ID =
            UUID.fromString("cd000000-0000-4000-8000-0000000000c5");
    private static final UUID ASSET_ID =
            UUID.fromString("cd000000-0000-4000-8000-0000000000c2");
    private static final UUID ATTEMPT_ID =
            UUID.fromString("cd000000-0000-4000-8000-0000000000c3");
    private static final UUID SESSION_ID =
            UUID.fromString("cd000000-0000-4000-8000-0000000000c4");
    /**
     * VB-6E: a CANONICAL logical storage reference, in the exact shape
     * {@code LocalAudioStorage} produces. Pre-VB-6E this was the loose string
     * {@code "tenants/tenant-a/promo.wav"}, which the dial path forwarded to
     * FreeSWITCH verbatim; the media resolver would now (correctly) refuse it.
     */
    private static final String AUDIO_REF =
            "audio/aa000000-0000-4000-8000-00000000000a/"
                    + "cd000000-0000-4000-8000-0000000000c2/promo.wav";

    /** The path the resolver produces for AUDIO_REF, which is what is dialled. */
    private static final String MEDIA_URI =
            "/usr/share/freeswitch/sounds/"
                    + "aa000000-0000-4000-8000-00000000000a/"
                    + "cd000000-0000-4000-8000-0000000000c2/promo.wav";

    @Mock
    private CallSessionRepository callSessionRepository;
    @Mock
    private com.shivang.obd.voice.call.CallLegRepository callLegRepository;
    @Mock
    private CallAttemptRepository callAttemptRepository;
    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignExecutionRepository executionRepository;
    @Mock
    private CampaignConfigurationService configurationService;
    @Mock
    private AudioAssetRepository audioAssetRepository;
    @Mock
    private VoiceMediaController mediaController;

    private PlayfileExecutionService service;
    private CallSession session;
    private CallAttempt attempt;
    private CampaignEntity campaign;
    private AudioAssetEntity asset;

    @BeforeEach
    void setUp() {
        service = new PlayfileExecutionService(
                callSessionRepository, callLegRepository, callAttemptRepository,
                campaignRepository, executionRepository, audioAssetRepository,
                // Real canonical validator (VB-5E) over the mocked audio repo:
                // the per-test asset stubs drive validation scenarios as before.
                new CampaignResourceValidationService(null, audioAssetRepository, null),
                new CampaignRuntimeConfigResolver(configurationService),
    // VB-6E: the real media URI resolver, so the translation under test is
    // exercised rather than stubbed out.
    mediaUriResolver(),
    mediaController);

        session = session(
                SESSION_ID,
                TENANT_A,
                UUID.fromString("dd000000-0000-4000-8000-0000000000d1"),
                UUID.fromString("dd000000-0000-4000-8000-0000000000d2"));
        session.setProviderCallId("fs-uuid-exec-1");
        session.setStatus(CallSessionStatus.ANSWERED);
        when(callSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        attempt = new CallAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setTenantId(TENANT_A);
        attempt.setCampaignId(CAMPAIGN_ID);
        attempt.setExecutionId(EXECUTION_ID);
        attempt.setStatus(CallAttemptStatus.IN_PROGRESS);
        when(callAttemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));

        campaign = new CampaignEntity();
        campaign.setId(CAMPAIGN_ID);
        campaign.setTenantId(TENANT_A);
        campaign.setCampaignType(CampaignType.PLAYFILE);
        campaign.setContentMode(ContentMode.AUDIO);
        campaign.setAudioAssetId(ASSET_ID);
        when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(CAMPAIGN_ID, TENANT_A))
                .thenReturn(Optional.of(campaign));
        // VB-6A correction: the execution resolves its mandatory immutable
        // snapshot â€” the snapshot carries the same values the live campaign
        // fixture has (no live fallback).
        var execution = new com.shivang.obd.campaign.CampaignExecution();
        execution.setId(EXECUTION_ID);
        execution.setCampaignId(CAMPAIGN_ID);
        execution.setTenantId(TENANT_A);
        execution.setConfigurationSnapshotId(UUID.randomUUID());
        when(executionRepository.findByIdAndDeletedAtIsNull(EXECUTION_ID))
                .thenReturn(Optional.of(execution));
        when(configurationService.requireExecutionSnapshot(execution))
                .thenReturn(CampaignExecutionConfiguration.materialize(
                        CAMPAIGN_ID, TENANT_A,
                        new CampaignConfigurationSnapshot(
                                CampaignType.PLAYFILE, null, null, ContentMode.AUDIO,
                                ASSET_ID, null,
                                null, null, null, null, null, null,
                                0, null, RetryStrategy.FIXED, null, false, null),
                        java.time.Instant.now()));

        asset = new AudioAssetEntity();
        asset.setId(ASSET_ID);
        asset.setTenantId(TENANT_A);
        asset.setStatus(AudioAssetStatus.APPROVED);
        asset.setStorageReference(AUDIO_REF);
        when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT_A))
                .thenReturn(Optional.of(asset));

        when(callLegRepository.findByCallSessionIdAndDeletedAtIsNull(session.getId()))
                .thenReturn(java.util.List.of());
    }

    /** Re-stubs the execution snapshot with the given configuration values. */
    private void stubSnapshot(CampaignType type, ContentMode mode, UUID audioAssetId) {
        when(configurationService.requireExecutionSnapshot(
                org.mockito.ArgumentMatchers.any(com.shivang.obd.campaign.CampaignExecution.class)))
            .thenReturn(CampaignExecutionConfiguration.materialize(
                    CAMPAIGN_ID, TENANT_A,
                    new CampaignConfigurationSnapshot(
                            type, null, null, mode, audioAssetId, null,
                            null, null, null, null, null, null,
                            0, null, RetryStrategy.FIXED, null, false, null),
                    java.time.Instant.now()));
    }

    @Nested
    class CampaignExecution {

        @Test
        @DisplayName("P1: PLAYFILE + AUDIO campaign requests playback with the asset reference")
        void p1_playfileActionTriggersPlayback() {
            service.onAnswered(session.getId(), ATTEMPT_ID);

            verify(mediaController).playAudio(
                    eq(session.getId()), any(), eq(MEDIA_URI));
            verify(mediaController, never()).terminateCall(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("P2: non-PLAYFILE campaign never invokes media playback")
        void p2_nonPlayfileNeverPlays() {
            // The snapshot's type drives the trigger decision (VB-6A correction).
            stubSnapshot(CampaignType.DTMF, ContentMode.AUDIO, ASSET_ID);

            service.onAnswered(session.getId(), ATTEMPT_ID);

            verify(mediaController, never()).playAudio(
                    any(UUID.class), any(), anyString());
            verify(mediaController, never()).terminateCall(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("P3: PLAYFILE campaign without an audio asset is rejected as config-invalid")
        void p3_missingAudioReferenceRejected() {
            // The snapshot's audio reference drives the trigger (VB-6A correction).
            stubSnapshot(CampaignType.PLAYFILE, ContentMode.AUDIO, null);

            service.onAnswered(session.getId(), ATTEMPT_ID);

            assertThat(session.getFailureCode())
                    .isEqualTo(PlayfileExecutionService.PLAYBACK_CONFIG_INVALID_CODE);
            // Config errors are permanent: no playback is attempted, call is
            // torn down via the media boundary.
            verify(mediaController, never()).playAudio(any(UUID.class), any(), anyString());
            verify(mediaController).terminateCall(session.getId(), "fs-uuid-exec-1");
        }

        @Test
        @DisplayName("PLAYFILE campaign with TTS content mode is rejected as config-invalid")
        void ttsContentModeRejected() {
            // The snapshot's content mode drives the trigger (VB-6A correction).
            stubSnapshot(CampaignType.PLAYFILE, ContentMode.TTS, ASSET_ID);

            service.onAnswered(session.getId(), ATTEMPT_ID);

            assertThat(session.getFailureCode())
                    .isEqualTo(PlayfileExecutionService.PLAYBACK_CONFIG_INVALID_CODE);
            verify(mediaController, never()).playAudio(any(UUID.class), any(), anyString());
        }

        @Test
        @DisplayName("unapproved asset is rejected as config-invalid")
        void unapprovedAssetRejected() {
            asset.setStatus(AudioAssetStatus.PENDING_APPROVAL);

            service.onAnswered(session.getId(), ATTEMPT_ID);

            assertThat(session.getFailureCode())
                    .isEqualTo(PlayfileExecutionService.PLAYBACK_CONFIG_INVALID_CODE);
            verify(mediaController, never()).playAudio(any(UUID.class), any(), anyString());
        }

        @Test
        @DisplayName("asset without a storage reference is rejected as config-invalid")
        void assetWithoutStorageReferenceRejected() {
            asset.setStorageReference(" ");

            service.onAnswered(session.getId(), ATTEMPT_ID);

            assertThat(session.getFailureCode())
                    .isEqualTo(PlayfileExecutionService.PLAYBACK_CONFIG_INVALID_CODE);
            verify(mediaController, never()).playAudio(any(UUID.class), any(), anyString());
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        @DisplayName("P24: tenant A cannot play tenant B's audio asset")
        void p24_crossTenantAssetRejected() {
            // The session belongs to tenant B (e.g. hostile/incorrect wiring);
            // the asset lookup is scoped to the *session's* tenant, so the
            // tenant-A-owned asset is not visible and playback is refused.
            session.setTenantId(TENANT_B);
            when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT_B))
                    .thenReturn(Optional.empty());

            service.onAnswered(session.getId(), ATTEMPT_ID);

            assertThat(session.getFailureCode())
                    .isEqualTo(PlayfileExecutionService.PLAYBACK_CONFIG_INVALID_CODE);
            verify(mediaController, never()).playAudio(any(UUID.class), any(), anyString());
        }

        @Test
        @DisplayName("campaign lookup is tenant-scoped: foreign campaign is not resolved")
        void campaignLookupTenantScoped() {
            attempt.setCampaignId(CAMPAIGN_ID);
            // Attempt claims tenant B, campaign exists only under tenant A â€”
            // the tenant-scoped finder returns empty, so no playback occurs.
            attempt.setTenantId(TENANT_B);
            when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(CAMPAIGN_ID, TENANT_B))
                    .thenReturn(Optional.empty());

            service.onAnswered(session.getId(), ATTEMPT_ID);

            verify(mediaController, never()).playAudio(any(UUID.class), any(), anyString());
            verify(mediaController, never()).terminateCall(any(UUID.class), anyString());
        }
    }

    @Nested
    class TriggerBehavior {

        @Test
        @DisplayName("duplicate ANSWER trigger is a no-op (session already past ANSWERED)")
        void duplicateTriggerNoOp() {
            session.setStatus(CallSessionStatus.PLAYING);

            service.onAnswered(session.getId(), ATTEMPT_ID);

            verify(mediaController, never()).playAudio(any(UUID.class), any(), anyString());
        }

        @Test
        @DisplayName("command failure is recorded as temporary PLAYBACK_FAILED")
        void commandFailureRecordedAsTemporary() {
            org.mockito.Mockito.doThrow(
                    new com.shivang.obd.telephony.EslException("channel gone"))
                    .when(mediaController)
                    .playAudio(any(UUID.class), any(), anyString());

            service.onAnswered(session.getId(), ATTEMPT_ID);

            assertThat(session.getFailureCode())
                    .isEqualTo(PlayfileExecutionService.PLAYBACK_FAILED_CODE);
            assertThat(session.getFailureReason()).contains("channel gone");
        }

        @Test
        @DisplayName("unknown call session is a safe no-op")
        void unknownSessionNoOp() {
            when(callSessionRepository.findById(session.getId())).thenReturn(Optional.empty());

            service.onAnswered(session.getId(), ATTEMPT_ID);

            verify(mediaController, never()).playAudio(any(UUID.class), any(), anyString());
        }

        @Test
        @DisplayName("null identifiers are safe no-ops")
        void nullIdentifiersNoOp() {
            service.onAnswered(null, null);

            verify(mediaController, never()).playAudio(any(UUID.class), any(), anyString());
        }
    }
    /** VB-6E: a real resolver over the FreeSWITCH-default media root. */
    private static com.shivang.obd.audio.MediaUriResolver mediaUriResolver() {
        com.shivang.obd.audio.AudioStorageProperties properties =
                new com.shivang.obd.audio.AudioStorageProperties();
        properties.setEnabled(true);
        properties.setFreeswitchMediaRoot("/usr/share/freeswitch/sounds");
        return new com.shivang.obd.audio.MediaUriResolver(properties);
    }
}