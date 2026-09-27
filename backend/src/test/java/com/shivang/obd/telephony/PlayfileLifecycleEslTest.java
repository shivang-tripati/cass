package com.shivang.obd.telephony;

import static com.shivang.obd.voice.VoiceTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.CallAttempt;
import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.campaign.CallAttemptStatus;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.media.PlaybackTrigger;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.time.Instant;
import java.util.List;
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
 * VB-1 call lifecycle and event tests (P4–P8, P13–P15, P26–P29).
 * <p>
 * Drives {@link EslEventService} with real ESL event sequences and verifies
 * the state machine: RINGING → ANSWERED → PLAYING → PLAYBACK_COMPLETED →
 * hangup, remote hangup during playback, playback failure teardown, and
 * idempotency of every duplicated event.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlayfileLifecycleEslTest {

    @Mock CallAttemptRepository attemptRepository;
    @Mock CallSessionRepository callSessionRepository;
    @Mock CallLegRepository callLegRepository;
    @Mock VoiceCapacityService voiceCapacity;
    @Mock VoiceMediaController mediaController;
    @Mock PlaybackTrigger playbackTrigger;
    @Mock com.shivang.obd.voice.dtmf.DtmfResultService dtmfResultService;

    EslEventService service;

    static final UUID TENANT = TENANT_A;
    static final UUID GATEWAY = UUID.fromString("aa000000-0000-4000-8000-00000000001a");
    static final String PROVIDER_CALL_ID = "fs-uuid-abc-123";

    CallAttempt attempt;
    CallSession session;
    CallLeg leg;

    @BeforeEach
    void setUp() {
        service = new EslEventService(attemptRepository, callSessionRepository, callLegRepository,
                voiceCapacity, mediaController, java.util.List.of(playbackTrigger),
                java.util.Optional.empty(), dtmfResultService, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());

        attempt = new CallAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setTenantId(TENANT);
        attempt.setStatus(CallAttemptStatus.IN_PROGRESS);
        attempt.setProviderCallId(PROVIDER_CALL_ID);

        session = new CallSession();
        session.setId(UUID.randomUUID());
        session.setTenantId(TENANT);
        session.setGatewayId(GATEWAY);
        session.setStatus(CallSessionStatus.DIALING);
        session.setProviderCallId(PROVIDER_CALL_ID);
        session.setInitiatedAt(Instant.now());

        leg = new CallLeg();
        leg.setId(UUID.randomUUID());
        leg.setCallSessionId(session.getId());
        leg.setStatus(CallLegStatus.DIALING);
        leg.setProviderCallId(PROVIDER_CALL_ID);

        lenientAttempt();
        lenientSession();
        lenientLeg();
    }

    private void lenientAttempt() {
        when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(PROVIDER_CALL_ID))
                .thenReturn(Optional.of(attempt));
        when(attemptRepository.save(any(CallAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void lenientSession() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(PROVIDER_CALL_ID))
                .thenReturn(Optional.of(session));
        when(callSessionRepository.save(any(CallSession.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void lenientLeg() {
        when(callLegRepository.findByCallSessionIdAndDeletedAtIsNull(session.getId()))
                .thenReturn(List.of(leg));
        when(callLegRepository.save(any(CallLeg.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private EslEvent event(String name, String... headerPairs) {
        EslEvent e = new EslEvent(name);
        e.addHeader("Call-UUID", PROVIDER_CALL_ID);
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            e.addHeader(headerPairs[i], headerPairs[i + 1]);
        }
        return e;
    }

    @Nested
    class CallLifecycle {

        @Test
        @DisplayName("P4: originate → CHANNEL_PROGRESS maps session+leg to RINGING")
        void p4_progressMapsToRinging() {
            boolean processed = service.processEvent(event("CHANNEL_PROGRESS"));

            assertThat(processed).isTrue();
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.RINGING);
            assertThat(leg.getStatus()).isEqualTo(CallLegStatus.RINGING);
        }

        @Test
        @DisplayName("P4b: CHANNEL_PROGRESS_MEDIA (early media) also maps to RINGING")
        void p4b_progressMediaAlsoRinging() {
            service.processEvent(event("CHANNEL_PROGRESS_MEDIA"));

            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.RINGING);
        }

        @Test
        @DisplayName("P5: CHANNEL_PROGRESS → CHANNEL_ANSWER transitions to ANSWERED")
        void p5_answerAfterRinging() {
            service.processEvent(event("CHANNEL_PROGRESS"));
            service.processEvent(event("CHANNEL_ANSWER"));

            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.ANSWERED);
            assertThat(session.getAnsweredAt()).isNotNull();
            assertThat(leg.getStatus()).isEqualTo(CallLegStatus.ANSWERED);
        }

        @Test
        @DisplayName("P6: PLAYBACK_START after ANSWERED transitions to PLAYING")
        void p6_playbackStartTransitionsToPlaying() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));

            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.PLAYING);
        }

        @Test
        @DisplayName("P7: PLAYBACK_STOP after PLAYING records playback completion")
        void p7_playbackStopCompletesPlayback() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));
            service.processEvent(event("PLAYBACK_STOP"));

            // Completion leaves PLAYING for the distinct PLAYBACK_COMPLETED
            // state; the campaign trigger has been dispatched (PLAYFILE
            // teardown / DTMF collection happens behind that boundary).
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.PLAYBACK_COMPLETED);
            verify(playbackTrigger).onPlaybackCompleted(session.getId(), attempt.getId());
        }

        @Test
        @DisplayName("P8: playback completion requests hangup, not reservation release")
        void p8_completionHangsUpViaMediaBoundary() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));
            service.processEvent(event("PLAYBACK_STOP"));

            // Single authoritative release path = CHANNEL_HANGUP, not the
            // playback completion. The completion is dispatched to the
            // campaign trigger; capacity release must NOT happen here.
            verify(playbackTrigger).onPlaybackCompleted(session.getId(), attempt.getId());
            verify(voiceCapacity, never()).release(any(UUID.class), any(UUID.class));
        }
    }

    @Nested
    class HangupBehavior {

        @Test
        @DisplayName("P13: remote hangup during playback finalizes and releases reservation")
        void p13_remoteHangupDuringPlayback() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.PLAYING);

            boolean processed = service.processEvent(event("CHANNEL_HANGUP", "Hangup-Cause", "NORMAL_CLEARING"));

            assertThat(processed).isTrue();
            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.COMPLETED);
            assertThat(session.getEndedAt()).isNotNull();
            verify(voiceCapacity).release(GATEWAY, TENANT);
            // PLAYING did not remain permanently.
            assertThat(session.getStatus()).isNotEqualTo(CallSessionStatus.PLAYING);
        }

        @Test
        @DisplayName("P14: duplicate hangup events are safe — second is ignored")
        void p14_duplicateHangupSafe() {
            service.processEvent(event("CHANNEL_HANGUP", "Hangup-Cause", "NORMAL_CLEARING"));
            boolean second = service.processEvent(event("CHANNEL_HANGUP", "Hangup-Cause", "NORMAL_CLEARING"));

            assertThat(second).isTrue(); // event routed, but state untouched
            verify(voiceCapacity, org.mockito.Mockito.times(1)).release(GATEWAY, TENANT);
        }

        @Test
        @DisplayName("P15: playback completion → hangup event → completed attempt")
        void p15_completionThenHangupFinalizesCompleted() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));
            service.processEvent(event("PLAYBACK_STOP"));
            service.processEvent(event("CHANNEL_HANGUP", "Hangup-Cause", "NORMAL_CLEARING"));

            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
            assertThat(attempt.getFailureCode()).isNull();
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.COMPLETED);
            verify(voiceCapacity).release(GATEWAY, TENANT);
        }

        @Test
        @DisplayName("P18: hangup releases reservation exactly once per call")
        void p18_hangupReleasesReservation() {
            service.processEvent(event("CHANNEL_HANGUP", "Hangup-Cause", "USER_BUSY"));

            verify(voiceCapacity).release(GATEWAY, TENANT);
            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(attempt.getFailureCode()).isEqualTo("BUSY");
        }
    }

    @Nested
    class PlaybackFailure {

        @Test
        @DisplayName("P12: PLAYBACK_ERROR requests teardown and marks failure on session")
        void p12_playbackErrorRecordsFailure() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));
            service.processEvent(event("PLAYBACK_ERROR", "Playback-Error", "FILE_NOT_FOUND"));

            verify(mediaController).terminateCall(session.getId(), PROVIDER_CALL_ID);
            assertThat(session.getFailureCode()).isEqualTo("PLAYBACK_FAILED");
        }

        @Test
        @DisplayName("P19: playback failure → hangup releases reservation and fails attempt")
        void p19_playbackFailureEventuallyReleasesReservation() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));
            service.processEvent(event("PLAYBACK_ERROR", "Playback-Error", "RESOURCE_ERROR"));
            service.processEvent(event("CHANNEL_HANGUP", "Hangup-Cause", "NORMAL_CLEARING"));

            // Playback failure preserves its failure classification even
            // though the hangup cause itself was NORMAL_CLEARING.
            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(attempt.getFailureCode()).isEqualTo("PLAYBACK_FAILED");
            verify(voiceCapacity).release(GATEWAY, TENANT);
        }

        @Test
        @DisplayName("P28: duplicate PLAYBACK_ERROR is safe — no double teardown/finalize")
        void p28_duplicatePlaybackErrorSafe() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));
            service.processEvent(event("PLAYBACK_ERROR", "Playback-Error", "RESOURCE_ERROR"));
            service.processEvent(event("PLAYBACK_ERROR", "Playback-Error", "RESOURCE_ERROR"));

            verify(mediaController, org.mockito.Mockito.times(1))
                    .terminateCall(session.getId(), PROVIDER_CALL_ID);
        }

        @Test
        @DisplayName("P22: config-invalid playback (PLAYBACK_CONFIG_INVALID) never completes as success")
        void p22_configInvalidNeverCompletes() {
            // PlayfileExecutionService records PLAYBACK_CONFIG_INVALID and tears
            // the call down; FreeSWITCH then hangs up with NORMAL_CLEARING. The
            // attempt must FAIL permanently — not COMPLETE because the hangup
            // cause itself was "successful".
            session.setFailureCode("PLAYBACK_CONFIG_INVALID");
            session.setFailureReason("Audio asset is not available for this tenant");

            service.processEvent(event("CHANNEL_HANGUP", "Hangup-Cause", "NORMAL_CLEARING"));

            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(attempt.getFailureCode()).isEqualTo("PLAYBACK_CONFIG_INVALID");
            assertThat(attempt.getFailureReason()).contains("not available for this tenant");
            verify(voiceCapacity).release(GATEWAY, TENANT);
        }
    }

    @Nested
    class Idempotency {

        @Test
        @DisplayName("P26: duplicate ANSWER is safe — answeredAt not overwritten, no regression")
        void p26_duplicateAnswerSafe() {
            service.processEvent(event("CHANNEL_ANSWER"));
            Instant firstAnsweredAt = session.getAnsweredAt();
            service.processEvent(event("CHANNEL_PROGRESS")); // late/racing progress
            service.processEvent(event("CHANNEL_ANSWER"));

            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.ANSWERED);
            assertThat(session.getAnsweredAt()).isEqualTo(firstAnsweredAt);
        }

        @Test
        @DisplayName("RINGING→RINGING→ANSWERED does not corrupt the lifecycle")
        void repeatedRingingThenAnswer() {
            service.processEvent(event("CHANNEL_PROGRESS"));
            service.processEvent(event("CHANNEL_PROGRESS_MEDIA"));
            service.processEvent(event("CHANNEL_ANSWER"));

            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.ANSWERED);
            assertThat(leg.getStatus()).isEqualTo(CallLegStatus.ANSWERED);
        }

        @Test
        @DisplayName("P27: duplicate PLAYBACK_STOP requests hangup only once")
        void p27_duplicatePlaybackStopSafe() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));
            service.processEvent(event("PLAYBACK_STOP"));
            service.processEvent(event("PLAYBACK_STOP"));

            verify(playbackTrigger, org.mockito.Mockito.times(1))
                    .onPlaybackCompleted(session.getId(), attempt.getId());
        }

        @Test
        @DisplayName("P29: duplicate HANGUP after playback flow remains safe")
        void p29_duplicateHangupInFullFlow() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));
            service.processEvent(event("PLAYBACK_STOP"));
            service.processEvent(event("CHANNEL_HANGUP", "Hangup-Cause", "NORMAL_CLEARING"));
            service.processEvent(event("CHANNEL_HANGUP", "Hangup-Cause", "NORMAL_CLEARING"));

            verify(voiceCapacity, org.mockito.Mockito.times(1)).release(GATEWAY, TENANT);
            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        }

        @Test
        @DisplayName("duplicate PLAYBACK_START does not leave session inconsistent")
        void duplicatePlaybackStartSafe() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));
            service.processEvent(event("PLAYBACK_START"));

            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.PLAYING);
        }

        @Test
        @DisplayName("answer trigger fires exactly once for the execution layer")
        void answerTriggerFiresOnce() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("CHANNEL_ANSWER"));

            verify(playbackTrigger, org.mockito.Mockito.times(1))
                    .onAnswered(eq(session.getId()), eq(attempt.getId()));
        }
    }

    @Nested
    class StateMachineSafety {

        @Test
        @DisplayName("QUEUED → PLAYING is rejected: PLAYBACK_START before answer is ignored")
        void queuedToPlayingRejected() {
            // Session still DIALING (never answered) — PLAYBACK_START ignored.
            service.processEvent(event("PLAYBACK_START"));

            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.DIALING);
        }

        @Test
        @DisplayName("RINGING → PLAYING is rejected: playback only starts from ANSWERED")
        void ringingToPlayingRejected() {
            service.processEvent(event("CHANNEL_PROGRESS"));
            service.processEvent(event("PLAYBACK_START"));

            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.RINGING);
        }

        @Test
        @DisplayName("HANGUP → PLAYING cannot occur after terminal state")
        void hangupToPlayingImpossible() {
            service.processEvent(event("CHANNEL_HANGUP", "Hangup-Cause", "NORMAL_CLEARING"));
            service.processEvent(event("PLAYBACK_START"));

            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.COMPLETED);
        }

        @Test
        @DisplayName("PLAYBACK_COMPLETED → PLAYING must not restart playback")
        void completedPlaybackDoesNotRestart() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("PLAYBACK_START"));
            service.processEvent(event("PLAYBACK_STOP")); // now PLAYBACK_COMPLETED
            service.processEvent(event("PLAYBACK_START")); // attempted restart

            // After completion the session is in PLAYBACK_COMPLETED; neither a
            // stray PLAYBACK_START (only valid from ANSWERED) nor a duplicate
            // PLAYBACK_STOP (only valid from PLAYING) may act — exactly one
            // teardown request is ever issued.
            service.processEvent(event("PLAYBACK_STOP"));
            verify(playbackTrigger, org.mockito.Mockito.times(1))
                    .onPlaybackCompleted(session.getId(), attempt.getId());
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.PLAYBACK_COMPLETED);
        }

        @Test
        @DisplayName("progress events never regress ANSWERED or terminal state")
        void progressNeverRegressesState() {
            service.processEvent(event("CHANNEL_ANSWER"));
            service.processEvent(event("CHANNEL_PROGRESS"));

            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.ANSWERED);
        }

        @Test
        @DisplayName("unknown provider call id is a safe no-op (P-RES6 contract)")
        void unknownUuidNoOp() {
            EslEvent e = new EslEvent("CHANNEL_ANSWER");
            e.addHeader("Call-UUID", "fs-unknown");

            assertThat(service.processEvent(e)).isFalse();
            verify(voiceCapacity, never()).release(any(UUID.class), any(UUID.class));
        }

        @Test
        @DisplayName("event without Call-UUID is ignored")
        void noCallUuidIgnored() {
            assertThat(service.processEvent(new EslEvent("CHANNEL_ANSWER"))).isFalse();
        }

        @Test
        @DisplayName("session without gateway does not release capacity")
        void noGatewayNoRelease() {
            session.setGatewayId(null);
            service.processEvent(event("CHANNEL_HANGUP", "Hangup-Cause", "NORMAL_CLEARING"));

            verify(voiceCapacity, never()).release(any(UUID.class), any(UUID.class));
        }
    }

    @Nested
    class NoPlaybackTriggerConfigured {

        @Test
        @DisplayName("answer processing works without a PlaybackTrigger bean (Optional.empty)")
        void answerWithoutTrigger() {
            EslEventService bare = new EslEventService(attemptRepository, callSessionRepository,
                    callLegRepository, voiceCapacity, mediaController, java.util.List.of(),
                    java.util.Optional.empty(), dtmfResultService, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());

            boolean processed = bare.processEvent(event("CHANNEL_ANSWER"));

            assertThat(processed).isTrue();
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.ANSWERED);
        }
    }
}
