package com.shivang.obd.campaign;

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
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.dtmf.DtmfInteraction;
import com.shivang.obd.voice.dtmf.DtmfInteractionRepository;
import com.shivang.obd.voice.dtmf.DtmfResultService;
import com.shivang.obd.voice.dtmf.DtmfResultType;
import java.time.Instant;
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
import tools.jackson.databind.ObjectMapper;

/**
 * VB-2 campaign execution tests (spec §16.A/F/G/H): PLAYBACK_COMPLETED →
 * interaction creation, digit collection, timeout, tenant-scoped audio
 * validation, idempotency of duplicate digits/timeouts, and reservation
 * neutrality (no release here — CHANNEL_HANGUP stays authoritative).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DtmfExecutionServiceTest {

    @Mock CallSessionRepository callSessionRepository;
    @Mock CallLegRepository callLegRepository;
    @Mock CallAttemptRepository callAttemptRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock CampaignExecutionRepository executionRepository;
    @Mock CampaignExecutionConfigurationRepository snapshotRepository;
    @Mock CampaignConfigurationService configurationService;
    @Mock CampaignRuntimeConfigResolver runtimeConfigResolver;
    @Mock AudioAssetRepository audioAssetRepository;
    @Mock DtmfInteractionRepository interactionRepository;
    @Mock DtmfResultService resultService;
    @Mock com.shivang.obd.voice.media.VoiceMediaController mediaController;

    DtmfExecutionService service;

    static final UUID TENANT_A = UUID.fromString("d3000000-0000-4000-8000-0000000000a1");
    static final UUID TENANT_B = UUID.fromString("d3000000-0000-4000-8000-0000000000b2");
    static final UUID SESSION_ID = UUID.fromString("d3000000-0000-4000-8000-0000000000c1");
    static final UUID ATTEMPT_ID = UUID.fromString("d3000000-0000-4000-8000-0000000000d1");
    static final UUID CAMPAIGN_ID = UUID.fromString("d3000000-0000-4000-8000-0000000000e1");
    static final UUID EXECUTION_ID = UUID.fromString("d3000000-0000-4000-8000-0000000000e2");
    static final UUID ASSET_ID = UUID.fromString("d3000000-0000-4000-8000-0000000000f1");
    static final UUID INTERACTION_ID = UUID.fromString("d3000000-0000-4000-8000-000000000111");
    static final UUID SNAPSHOT_ID = UUID.fromString("d3000000-0000-4000-8000-0000000000e3");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    CallSession session;
    CallAttempt attempt;
    CampaignEntity campaign;
    AudioAssetEntity asset;
    DtmfInteraction interaction;

    @BeforeEach
    void setUp() {
        service = new DtmfExecutionService(callSessionRepository, callLegRepository,
                callAttemptRepository, campaignRepository, executionRepository,
                audioAssetRepository,
                // Real canonical validator (VB-5E) over the mocked audio repo:
                // the per-test asset stubs drive validation scenarios as before.
                new CampaignResourceValidationService(null, audioAssetRepository, null),
                new CampaignRuntimeConfigResolver(configurationService),
                interactionRepository, resultService, mediaController,
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));

        session = new CallSession();
        session.setId(SESSION_ID);
        session.setTenantId(TENANT_A);
        session.setStatus(CallSessionStatus.ANSWERED);
        session.setProviderCallId("fs-uuid-1");
        session.setInitiatedAt(Instant.now());

        attempt = new CallAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setTenantId(TENANT_A);
        attempt.setCampaignId(CAMPAIGN_ID);
        attempt.setExecutionId(EXECUTION_ID);
        attempt.setStatus(CallAttemptStatus.IN_PROGRESS);

        campaign = new CampaignEntity();
        campaign.setId(CAMPAIGN_ID);
        campaign.setTenantId(TENANT_A);
        campaign.setCampaignType(CampaignType.DTMF);
        campaign.setContentMode(ContentMode.AUDIO);
        campaign.setAudioAssetId(ASSET_ID);
        campaign.setTypeConfig(MAPPER.readTree(
                "{\"dtmf\": {\"expected\": \"1\", \"timeoutSecs\": 10}}"));

        asset = new AudioAssetEntity();
        asset.setId(ASSET_ID);
        asset.setTenantId(TENANT_A);
        asset.setStatus(AudioAssetStatus.APPROVED);
        asset.setStorageReference("sounds/en/prompt.wav");

        interaction = new DtmfInteraction();
        interaction.setId(INTERACTION_ID);
        interaction.setTenantId(TENANT_A);
        interaction.setCallSessionId(SESSION_ID);
        interaction.setCallAttemptId(ATTEMPT_ID);
        interaction.setExpectedInput("1");
        interaction.setMaxDigits(1);
        interaction.setTimeoutSecs(10);
        interaction.setCollectedDigits("");
        interaction.setResult(DtmfResultType.COLLECTING);
        interaction.setExpiresAt(Instant.now().plusSeconds(10));

        when(callSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(callAttemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(CAMPAIGN_ID, TENANT_A))
                .thenReturn(Optional.of(campaign));
        // VB-6A correction: the trigger resolves the execution's immutable
        // snapshot config — mandatory, no live-campaign fallback. The stubbed
        // snapshot carries the same values the live campaign fixture has.
        var execution = new CampaignExecution();
        execution.setId(EXECUTION_ID);
        execution.setCampaignId(CAMPAIGN_ID);
        execution.setTenantId(TENANT_A);
        execution.setConfigurationSnapshotId(SNAPSHOT_ID);
        when(executionRepository.findByIdAndDeletedAtIsNull(EXECUTION_ID))
                .thenReturn(Optional.of(execution));
        when(configurationService.requireExecutionSnapshot(execution))
                .thenReturn(snapshot(TENANT_A, CAMPAIGN_ID, ASSET_ID));
        when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT_A))
                .thenReturn(Optional.of(asset));
        when(callLegRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(java.util.List.of());
        // No interaction exists until onPlaybackCompleted creates one; the
        // digit/timeout suites stub the active interaction explicitly.
        when(interactionRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.empty());
        when(resultService.finalizeInteraction(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> Optional.of(interaction));
    }

    /** Execution-owned snapshot fixture matching the live campaign values. */
    static CampaignExecutionConfiguration snapshot(
            UUID tenantId, UUID campaignId, UUID audioAssetId) {
        return CampaignExecutionConfiguration.materialize(
                campaignId, tenantId,
                new CampaignConfigurationSnapshot(
                        CampaignType.DTMF, null, null, ContentMode.AUDIO,
                        audioAssetId, null,
                        null, null, null, null, null, null, null,
                        0, null, RetryStrategy.FIXED,
                        MAPPER.readTree(
                                "{\"dtmf\": {\"expected\": \"1\", \"timeoutSecs\": 10}}"),
                        false, null),
                Instant.now());
    }

    /** Activates the persisted COLLECTING interaction for input-path tests. */
    private void givenActiveInteraction() {
        interaction.setResult(DtmfResultType.COLLECTING);
        when(interactionRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(interaction));
    }

    private void playThroughPlaybackCompleted() {
        session.setStatus(CallSessionStatus.ANSWERED);
        service.onAnswered(SESSION_ID, ATTEMPT_ID);
        verify(mediaController).playAudio(any(), any(), anyString());

        session.setStatus(CallSessionStatus.PLAYING);
        session.setStatus(CallSessionStatus.PLAYBACK_COMPLETED);
        service.onPlaybackCompleted(SESSION_ID, ATTEMPT_ID);
    }

    /** Re-stubs the execution snapshot with the given configuration values. */
    private void stubSnapshot(CampaignType type, tools.jackson.databind.JsonNode typeConfig) {
        when(configurationService.requireExecutionSnapshot(
                org.mockito.ArgumentMatchers.any(CampaignExecution.class)))
            .thenReturn(CampaignExecutionConfiguration.materialize(
                    CAMPAIGN_ID, TENANT_A,
                    new CampaignConfigurationSnapshot(
                            type, null, null, ContentMode.AUDIO,
                            ASSET_ID, null,
                            null, null, null, null, null, null, null,
                            0, null, RetryStrategy.FIXED,
                            typeConfig, false, null),
                    Instant.now()));
    }

    @Nested
    class InteractionCreation {

        @Test
        @DisplayName("PLAYBACK_COMPLETED for DTMF campaign creates interaction and enters WAITING_FOR_DTMF")
        void createsInteractionOnCompletion() {
            playThroughPlaybackCompleted();

            verify(interactionRepository).save(any(DtmfInteraction.class));
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.WAITING_FOR_DTMF);
            assertThat(interaction.getExpectedInput()).isEqualTo("1");
            assertThat(interaction.getResult()).isEqualTo(DtmfResultType.COLLECTING);
            // No hangup on entering collection.
            verify(mediaController, never()).terminateCall(any(), anyString());
        }

        @Test
        @DisplayName("PLAYFILE campaign playback completion is not handled by the DTMF trigger")
        void playfileCompletionIgnored() {
            session.setStatus(CallSessionStatus.PLAYBACK_COMPLETED);
            // The snapshot's type drives the trigger decision (VB-6A correction).
            stubSnapshot(CampaignType.PLAYFILE, null);

            service.onPlaybackCompleted(SESSION_ID, ATTEMPT_ID);

            verify(interactionRepository, never()).save(any(DtmfInteraction.class));
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.PLAYBACK_COMPLETED);
        }

        @Test
        @DisplayName("duplicate playback completion never creates a second interaction")
        void duplicateCompletionIgnored() {
            playThroughPlaybackCompleted();
            int savesBefore = 1;

            // Session has already left PLAYBACK_COMPLETED; a second event is a no-op.
            service.onPlaybackCompleted(SESSION_ID, ATTEMPT_ID);

            verify(interactionRepository, org.mockito.Mockito.times(savesBefore))
                    .save(any(DtmfInteraction.class));
        }

        @Test
        @DisplayName("invalid typeConfig fails the interaction as DTMF_CONFIG_INVALID (permanent)")
        void invalidConfigPermanentFailure() {
            session.setStatus(CallSessionStatus.PLAYBACK_COMPLETED);
            // The snapshot's typeConfig drives the trigger (VB-6A correction):
            // an unparseable payload surfaces as DTMF_CONFIG_INVALID.
            stubSnapshot(CampaignType.DTMF,
                    MAPPER.readTree("{\"dtmf\": {\"expected\": \"abc\"}}"));

            service.onPlaybackCompleted(SESSION_ID, ATTEMPT_ID);

            assertThat(session.getFailureCode()).isEqualTo(DtmfExecutionService.DTMF_CONFIG_INVALID_CODE);
            verify(interactionRepository, never()).save(any(DtmfInteraction.class));
            // Config-invalid tears the call down; hangup finalizes the attempt.
            verify(mediaController).terminateCall(SESSION_ID, "fs-uuid-1");
        }

        @Test
        @DisplayName("missing typeConfig fails the interaction as DTMF_CONFIG_INVALID")
        void missingConfigPermanentFailure() {
            session.setStatus(CallSessionStatus.PLAYBACK_COMPLETED);
            // The snapshot's typeConfig drives the trigger (VB-6A correction).
            stubSnapshot(CampaignType.DTMF, null);

            service.onPlaybackCompleted(SESSION_ID, ATTEMPT_ID);

            assertThat(session.getFailureCode()).isEqualTo(DtmfExecutionService.DTMF_CONFIG_INVALID_CODE);
            verify(interactionRepository, never()).save(any(DtmfInteraction.class));
        }
    }

    @Nested
    class AudioValidation {

        @Test
        @DisplayName("DTMF campaign audio is validated tenant-scoped and played")
        void dtmfCampaignPlaysAudio() {
            service.onAnswered(SESSION_ID, ATTEMPT_ID);

            verify(mediaController).playAudio(any(), any(), anyString());
        }

        @Test
        @DisplayName("cross-tenant audio asset is rejected as config-invalid (fail closed)")
        void crossTenantAssetRejected() {
            when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT_A))
                    .thenReturn(Optional.empty());

            service.onAnswered(SESSION_ID, ATTEMPT_ID);

            assertThat(session.getFailureCode()).isEqualTo(DtmfExecutionService.DTMF_CONFIG_INVALID_CODE);
            verify(mediaController, never()).playAudio(any(), any(), anyString());
        }
    }

    @Nested
    class DigitCollection {

        @org.junit.jupiter.api.BeforeEach
        void activeInteraction() {
            playThroughPlaybackCompleted();
            givenActiveInteraction();
        }

        @Test
        @DisplayName("expected digit → VALID result claimed and call hung up")
        void expectedDigitValid() {
            service.onDtmfDigit(SESSION_ID, ATTEMPT_ID, "1");

            verify(resultService).finalizeInteraction(eq(INTERACTION_ID), eq(DtmfResultType.VALID), anyString(), anyString());
            verify(mediaController).terminateCall(SESSION_ID, null);
        }

        @Test
        @DisplayName("unexpected digit → INVALID result claimed and call hung up")
        void unexpectedDigitInvalid() {
            service.onDtmfDigit(SESSION_ID, ATTEMPT_ID, "2");

            verify(resultService).finalizeInteraction(eq(INTERACTION_ID), eq(DtmfResultType.INVALID), anyString(), anyString());
            verify(mediaController).terminateCall(SESSION_ID, null);
        }

        @Test
        @DisplayName("digit while still in playback (no interaction) is ignored")
        void digitWithoutInteractionIgnored() {
            when(interactionRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION_ID))
                    .thenReturn(Optional.empty());

            service.onDtmfDigit(SESSION_ID, ATTEMPT_ID, "1");

            verify(resultService, never()).finalizeInteraction(any(), any(), anyString(), anyString());
            verify(mediaController, never()).terminateCall(any(), anyString());
        }

        @Test
        @DisplayName("duplicate digit after terminal result does not act again")
        void duplicateDigitAfterTerminalNoOp() {
            interaction.setResult(DtmfResultType.VALID);

            service.onDtmfDigit(SESSION_ID, ATTEMPT_ID, "1");

            verify(resultService, never()).finalizeInteraction(any(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("duplicate valid digit claims once — no double hangup")
        void duplicateValidDigitSingleClaim() {
            // Simulate the racing winner: second claim attempt returns empty.
            when(resultService.finalizeInteraction(any(), any(), anyString(), anyString()))
                    .thenReturn(Optional.of(interaction))
                    .thenReturn(Optional.empty());

            service.onDtmfDigit(SESSION_ID, ATTEMPT_ID, "1");
            service.onDtmfDigit(SESSION_ID, ATTEMPT_ID, "1");

            verify(mediaController, org.mockito.Mockito.times(1)).terminateCall(SESSION_ID, null);
        }

        @Test
        @DisplayName("digit arriving after the deadline is left to the timeout path")
        void digitAfterDeadlineIgnored() {
            interaction.setExpiresAt(Instant.now().minusSeconds(1));

            service.onDtmfDigit(SESSION_ID, ATTEMPT_ID, "1");

            verify(resultService, never()).finalizeInteraction(any(), any(), anyString(), anyString());
        }
    }

    @Nested
    class Timeout {

        @org.junit.jupiter.api.BeforeEach
        void activeInteraction() {
            playThroughPlaybackCompleted();
            givenActiveInteraction();
        }

        @Test
        @DisplayName("timeout finalizes TIMEOUT and hangs the call up")
        void timeoutFinalizesAndHangsUp() {
            service.onDtmfTimeout(SESSION_ID);

            verify(resultService).finalizeInteraction(eq(INTERACTION_ID), eq(DtmfResultType.TIMEOUT), anyString(), anyString());
            verify(mediaController).terminateCall(SESSION_ID, null);
        }

        @Test
        @DisplayName("duplicate timeout executes only once")
        void duplicateTimeoutSingleAction() {
            when(resultService.finalizeInteraction(any(), any(), anyString(), anyString()))
                    .thenReturn(Optional.of(interaction))
                    .thenReturn(Optional.empty());

            service.onDtmfTimeout(SESSION_ID);
            service.onDtmfTimeout(SESSION_ID);

            verify(mediaController, org.mockito.Mockito.times(1)).terminateCall(SESSION_ID, null);
        }

        @Test
        @DisplayName("timeout for a session without interaction is a safe no-op (stale scan row)")
        void staleTimeoutNoOp() {
            when(interactionRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION_ID))
                    .thenReturn(Optional.empty());

            service.onDtmfTimeout(SESSION_ID);

            verify(resultService, never()).finalizeInteraction(any(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("timeout after a valid digit does not overwrite the result or re-hangup")
        void timeoutAfterValidDoesNotOverwrite() {
            interaction.setResult(DtmfResultType.VALID);

            service.onDtmfTimeout(SESSION_ID);

            verify(resultService, never()).finalizeInteraction(any(), any(), anyString(), anyString());
            verify(mediaController, never()).terminateCall(any(), anyString());
        }
    }

    @Nested
    class ReservationNeutrality {

        @Test
        @DisplayName("DTMF results never release capacity — CHANNEL_HANGUP stays authoritative")
        void noCapacityTouchHere() {
            // This service has no VoiceCapacityService dependency at all:
            // asserting the media boundary sees only play/terminate requests.
            playThroughPlaybackCompleted();
            givenActiveInteraction();
            service.onDtmfDigit(SESSION_ID, ATTEMPT_ID, "1");

            verify(mediaController, org.mockito.Mockito.times(1)).terminateCall(any(), any());
            verify(mediaController, org.mockito.Mockito.times(1)).playAudio(any(), any(), anyString());
        }
    }
}
