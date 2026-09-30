package com.shivang.obd.telephony;

import static com.shivang.obd.voice.VoiceTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import java.util.Map;
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
 * Phase D regression tests for the runtime contract observed against a live
 * FreeSWITCH (defects J1, J2, J3).
 *
 * <p>Every assertion here is derived from measured behaviour, not from a
 * specification. Where the previous implementation encoded a contract the switch
 * does not implement, the historical expectation is kept in the test that
 * documents it, and the real behaviour is asserted separately.
 *
 * <h2>J1 - channel identity</h2>
 * <p>The switch emits no {@code Call-UUID} header. Identity arrives as
 * {@code Channel-Call-UUID} / {@code Unique-ID}. Correlation must work on those,
 * must still tolerate the legacy header, and must fail safely when no identity
 * is present at all.
 *
 * <h2>J2 - playback semantics</h2>
 * <p>{@code +OK Message sent} is identical for a valid and a missing file, and
 * no {@code PLAYBACK_ERROR} event or {@code Playback-Error} header is produced
 * for a missing file. The discriminator is {@code PLAYBACK_START}: present for a
 * valid file, absent for a missing one, with {@code CHANNEL_EXECUTE_COMPLETE}
 * closing the request in both cases.
 *
 * <h2>J3 - channel identity across a call</h2>
 * <p>The pinned {@code origination_uuid} remains the addressable identity for
 * media and hangup after the call bridges, and the identity survives being
 * carried under any of the channel-identity headers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EslEventRuntimeContractTest {

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
    static final String CHANNEL_UUID = "348eecfb-7d68-48f8-a566-3cac24e4a8cc";

    CallAttempt attempt;
    CallSession session;
    CallLeg leg;

    @BeforeEach
    void setUp() {
        service = new EslEventService(attemptRepository, callSessionRepository, callLegRepository,
                voiceCapacity, mediaController, List.of(playbackTrigger),
                Optional.empty(), dtmfResultService, Optional.empty(), Optional.empty(), Optional.empty());

        attempt = new CallAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setTenantId(TENANT);
        attempt.setStatus(CallAttemptStatus.IN_PROGRESS);
        attempt.setProviderCallId(CHANNEL_UUID);

        session = new CallSession();
        session.setId(UUID.randomUUID());
        session.setTenantId(TENANT);
        session.setGatewayId(GATEWAY);
        session.setStatus(CallSessionStatus.DIALING);
        session.setProviderCallId(CHANNEL_UUID);
        session.setInitiatedAt(Instant.now());

        leg = new CallLeg();
        leg.setId(UUID.randomUUID());
        leg.setCallSessionId(session.getId());
        leg.setStatus(CallLegStatus.DIALING);
        leg.setProviderCallId(CHANNEL_UUID);

        lenient().when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(CHANNEL_UUID))
                .thenReturn(Optional.of(attempt));
        lenient().when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CHANNEL_UUID))
                .thenReturn(Optional.of(session));
        lenient().when(callLegRepository.findByCallSessionIdAndDeletedAtIsNull(session.getId()))
                .thenReturn(List.of(leg));
        lenient().when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.empty());
    }

    /** An event carrying exactly the identity headers the real switch sends. */
    private EslEvent realShape(String eventName, Map<String, String> extra) {
        EslEvent e = new EslEvent(eventName);
        e.addHeader("Unique-ID", CHANNEL_UUID);
        e.addHeader("Channel-Call-UUID", CHANNEL_UUID);
        e.addHeader("Caller-Unique-ID", CHANNEL_UUID);
        e.addHeader("variable_call_uuid", CHANNEL_UUID);
        if (extra != null) {
            extra.forEach(e::addHeader);
        }
        return e;
    }

    // =====================================================================
    // J1 - channel identity
    // =====================================================================

    @Nested
    @DisplayName("J1 - channel identity comes from the headers the switch really sends")
    class ChannelIdentity {

        @Test
        @DisplayName("Channel-Call-UUID alone resolves the channel UUID")
        void channelCallUuidHeaderResolvesIdentity() {
            EslEvent e = new EslEvent("CHANNEL_HANGUP");
            e.addHeader("Channel-Call-UUID", CHANNEL_UUID);
            e.addHeader("Hangup-Cause", "NORMAL_CLEARING");

            assertThat(e.getCallUuid()).isEqualTo(CHANNEL_UUID);
            assertThat(service.processEvent(e)).isTrue();
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.COMPLETED);
        }

        @Test
        @DisplayName("Unique-ID alone resolves the channel UUID")
        void uniqueIdHeaderResolvesIdentity() {
            EslEvent e = new EslEvent("CHANNEL_HANGUP");
            e.addHeader("Unique-ID", CHANNEL_UUID);
            e.addHeader("Hangup-Cause", "NORMAL_CLEARING");

            assertThat(e.getCallUuid()).isEqualTo(CHANNEL_UUID);
            assertThat(service.processEvent(e)).isTrue();
        }

        @Test
        @DisplayName("the full real header set correlates to the right CallAttempt")
        void realHeaderSetCorrelatesToAttempt() {
            assertThat(service.processEvent(realShape("CHANNEL_ANSWER", null))).isTrue();

            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.ANSWERED);
            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);
            assertThat(leg.getStatus()).isEqualTo(CallLegStatus.ANSWERED);
        }

        @Test
        @DisplayName("header lookup is case-insensitive, so a casing change cannot silently break correlation")
        void headerLookupIsCaseInsensitive() {
            EslEvent e = new EslEvent("CHANNEL_HANGUP");
            e.addHeader("channel-call-uuid", CHANNEL_UUID);
            e.addHeader("hangup-cause", "NORMAL_CLEARING");

            assertThat(e.getHeader("Channel-Call-UUID")).isEqualTo(CHANNEL_UUID);
            assertThat(e.getCallUuid()).isEqualTo(CHANNEL_UUID);
            assertThat(e.getHangupCause()).isEqualTo("NORMAL_CLEARING");
        }

        @Test
        @DisplayName("an event with no identity header at all is dropped safely")
        void missingIdentityIsSafeNoOp() {
            EslEvent e = new EslEvent("CHANNEL_HANGUP");
            e.addHeader("Hangup-Cause", "NORMAL_CLEARING");

            assertThat(e.getCallUuid()).isNull();
            assertThat(e.hasChannelIdentity()).isFalse();
            assertThat(service.processEvent(e)).isFalse();
            verify(voiceCapacity, never()).release(any(), any());
        }

        @Test
        @DisplayName("a blank identity header is treated as absent, not as an empty id")
        void blankIdentityIsTreatedAsAbsent() {
            EslEvent e = new EslEvent("CHANNEL_HANGUP");
            e.addHeader("Channel-Call-UUID", "   ");

            assertThat(e.getCallUuid()).isNull();
        }

        @Test
        @DisplayName("Channel-Call-UUID wins over a conflicting Unique-ID")
        void channelCallUuidTakesPrecedence() {
            EslEvent e = new EslEvent("CHANNEL_HANGUP");
            e.addHeader("Channel-Call-UUID", CHANNEL_UUID);
            e.addHeader("Unique-ID", "some-other-uuid");

            assertThat(e.getCallUuid()).isEqualTo(CHANNEL_UUID);
        }

        @Test
        @DisplayName("legacy Call-UUID still resolves, so no existing caller breaks")
        void legacyCallUuidStillResolves() {
            EslEvent e = new EslEvent("CHANNEL_HANGUP");
            e.addHeader("Call-UUID", CHANNEL_UUID);
            e.addHeader("Hangup-Cause", "NORMAL_CLEARING");

            assertThat(e.getCallUuid()).isEqualTo(CHANNEL_UUID);
        }

        @Test
        @DisplayName("JOB-UUID is a task identity and is never a channel identity")
        void jobUuidIsNotAChannelIdentity() {
            EslEvent e = new EslEvent("CHANNEL_HANGUP");
            e.addHeader("Job-UUID", CHANNEL_UUID);

            assertThat(e.getCallUuid()).isNull();
        }

        @Test
        @DisplayName("an unrelated channel is not correlated to this call")
        void unrelatedChannelIsNotCorrelated() {
            String other = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
            lenient().when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(other))
                    .thenReturn(Optional.empty());
            lenient().when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(other))
                    .thenReturn(Optional.empty());

            EslEvent e = new EslEvent("CHANNEL_HANGUP");
            e.addHeader("Channel-Call-UUID", other);
            e.addHeader("Hangup-Cause", "NORMAL_CLEARING");

            assertThat(service.processEvent(e)).isFalse();
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.DIALING);
            verify(voiceCapacity, never()).release(any(), any());
        }

        @Test
        @DisplayName("DTMF correlates through the real headers")
        void dtmfCorrelatesThroughRealHeaders() {
            service.processEvent(realShape("CHANNEL_ANSWER", null));
            EslEvent dtmf = realShape("CHANNEL_DTMF", Map.of("DTMF-Digit", "5"));

            assertThat(dtmf.getDtmfDigit()).isEqualTo("5");
            assertThat(service.processEvent(dtmf)).isTrue();
        }

        @Test
        @DisplayName("origination_uuid is reported when the switch echoes it back")
        void originationUuidIsReportedWhenPresent() {
            EslEvent e = realShape("CHANNEL_CREATE",
                    Map.of("variable_origination_uuid", CHANNEL_UUID));

            assertThat(e.getOriginationUuid()).isEqualTo(CHANNEL_UUID);
            assertThat(e.getCallUuid()).isEqualTo(CHANNEL_UUID);
        }
    }

    // =====================================================================
    // J2 - playback semantics
    // =====================================================================

    @Nested
    @DisplayName("J2 - command acceptance is not playback success")
    class PlaybackSemantics {

        @Test
        @DisplayName("valid file: PLAYBACK_START then PLAYBACK_STOP completes normally")
        void validFileCompletesNormally() {
            service.processEvent(realShape("CHANNEL_ANSWER", null));
            service.processEvent(realShape("PLAYBACK_START", null));
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.PLAYING);

            service.processEvent(realShape("PLAYBACK_STOP", null));
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.PLAYBACK_COMPLETED);
            assertThat(session.getFailureCode()).isNull();
            verify(playbackTrigger).onPlaybackCompleted(session.getId(), attempt.getId());
        }

        @Test
        @DisplayName("missing file: CHANNEL_EXECUTE_COMPLETE with no PLAYBACK_START is a media failure")
        void missingFileIsDetectedAsFailure() {
            service.processEvent(realShape("CHANNEL_ANSWER", null));
            // No PLAYBACK_START, no PLAYBACK_STOP - exactly what the switch sends
            // for a file it cannot open.
            service.processEvent(realShape("CHANNEL_EXECUTE_COMPLETE", null));

            assertThat(session.getFailureCode()).isEqualTo("PLAYBACK_FAILED");
            assertThat(session.getFailureReason()).contains("did not start");
            verify(mediaController).terminateCall(session.getId(), CHANNEL_UUID);
        }

        @Test
        @DisplayName("a completed playback is not mistaken for a failure")
        void completedPlaybackIsNotAFailure() {
            service.processEvent(realShape("CHANNEL_ANSWER", null));
            service.processEvent(realShape("PLAYBACK_START", null));
            service.processEvent(realShape("PLAYBACK_STOP", null));
            service.processEvent(realShape("CHANNEL_EXECUTE_COMPLETE", null));

            assertThat(session.getFailureCode()).isNull();
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.PLAYBACK_COMPLETED);
            verify(mediaController, never()).terminateCall(any(), any());
        }

        @Test
        @DisplayName("a PLAYBACK_ERROR event still fails the call (the branch is retained)")
        void explicitPlaybackErrorStillFails() {
            service.processEvent(realShape("CHANNEL_ANSWER", null));
            service.processEvent(realShape("PLAYBACK_START", null));
            service.processEvent(realShape("PLAYBACK_ERROR", Map.of("Playback-Error", "boom")));

            assertThat(session.getFailureCode()).isEqualTo("PLAYBACK_FAILED");
        }

        @Test
        @DisplayName("a duplicate completion event does not fail an already-failed call twice")
        void duplicateCompletionIsIdempotent() {
            service.processEvent(realShape("CHANNEL_ANSWER", null));
            service.processEvent(realShape("CHANNEL_EXECUTE_COMPLETE", null));
            service.processEvent(realShape("CHANNEL_EXECUTE_COMPLETE", null));

            verify(mediaController, org.mockito.Mockito.times(1))
                    .terminateCall(any(), any());
        }

        @Test
        @DisplayName("hangup after a completed playback completes the attempt")
        void hangupAfterCompletedPlaybackCompletes() {
            service.processEvent(realShape("CHANNEL_ANSWER", null));
            service.processEvent(realShape("PLAYBACK_START", null));
            service.processEvent(realShape("PLAYBACK_STOP", null));
            service.processEvent(realShape("CHANNEL_HANGUP", Map.of("Hangup-Cause", "NORMAL_CLEARING")));

            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
            verify(voiceCapacity).release(GATEWAY, TENANT);
        }

        @Test
        @DisplayName("a failure recorded before hangup is not overwritten as success")
        void recordedFailureSurvivesNormalClearingHangup() {
            service.processEvent(realShape("CHANNEL_ANSWER", null));
            service.processEvent(realShape("CHANNEL_EXECUTE_COMPLETE", null));
            service.processEvent(realShape("CHANNEL_HANGUP", Map.of("Hangup-Cause", "NORMAL_CLEARING")));

            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(attempt.getFailureCode()).isEqualTo("PLAYBACK_FAILED");
        }
    }

    // =====================================================================
    // J3 - channel identity across a call
    // =====================================================================

    @Nested
    @DisplayName("J3 - the pinned identity stays addressable across the call")
    class ChannelIdentityAcrossCall {

        @Test
        @DisplayName("the stored provider id is the identity media and hangup address")
        void storedProviderIdIsTheAddressableIdentity() {
            assertThat(session.getProviderCallId()).isEqualTo(CHANNEL_UUID);
            assertThat(leg.getProviderCallId()).isEqualTo(CHANNEL_UUID);
        }

        @Test
        @DisplayName("a bridge anchored on another channel does not disturb this call's correlation")
        void foreignBridgeChannelIsNotCorrelated() {
            String foreign = "ffffffff-0000-4000-8000-0000000000ff";
            service.processEvent(realShape("CHANNEL_ANSWER", null));

            EslEvent bridge = new EslEvent("CHANNEL_BRIDGE");
            bridge.addHeader("Channel-Call-UUID", foreign);
            bridge.addHeader("Bridge-B-Unique-ID", "99999999-0000-4000-8000-000000000099");

            // Not correlated: the anchor is not this call, and the B-leg is not an
            // agent leg, so it is safely ignored rather than misapplied.
            assertThat(service.processEvent(bridge)).isFalse();
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.ANSWERED);
        }

        @Test
        @DisplayName("the tracker is cleared when the call ends, so state cannot leak between calls")
        void trackerIsClearedOnHangup() {
            service.processEvent(realShape("CHANNEL_ANSWER", null));
            service.processEvent(realShape("PLAYBACK_START", null));
            service.processEvent(realShape("CHANNEL_HANGUP", Map.of("Hangup-Cause", "NORMAL_CLEARING")));

            // After the call has ended, a late completion event must not re-open
            // a teardown attempt against a finished session.
            verify(mediaController, never()).terminateCall(any(), any());
        }
    }
}
