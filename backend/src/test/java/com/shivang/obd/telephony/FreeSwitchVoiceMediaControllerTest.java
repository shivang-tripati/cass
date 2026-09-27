package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VB-1 FreeSWITCH contract tests (P9, P10): the media adapter is tested at
 * the ESL command level — not just "mediaService.playFile was called". The
 * important contract is the actual FreeSWITCH command and the channel UUID
 * used as its target.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreeSwitchVoiceMediaControllerTest {

    private static final UUID CALL_SESSION_ID =
            UUID.fromString("cc000000-0000-4000-8000-0000000000c1");
    private static final UUID LEG_ID =
            UUID.fromString("cc000000-0000-4000-8000-0000000000c2");
    private static final String CHANNEL_UUID = "fs-channel-uuid-1234";
    private static final String AUDIO_PATH = "/usr/share/freeswitch/sounds/tenant-a/promo.wav";

    @Mock
    private FreeSwitchProperties properties;

    @Mock
    private CallSessionRepository callSessionRepository;

    private FreeSwitchVoiceMediaController controller() {
        return new FreeSwitchVoiceMediaController(properties, callSessionRepository,
                org.mockito.Mockito.mock(com.shivang.obd.voice.call.CallLegRepository.class));
    }

    private CallSession sessionWithProviderCallId() {
        CallSession session = new CallSession();
        session.setId(CALL_SESSION_ID);
        session.setProviderCallId(CHANNEL_UUID);
        return session;
    }

    @Test
    @DisplayName("P10: playAudio issues uuid_broadcast <uuid> <file> aleg on the correct channel")
    void playAudioIssuesUuidBroadcast() {
        when(callSessionRepository.findById(CALL_SESSION_ID))
                .thenReturn(Optional.of(sessionWithProviderCallId()));

        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class,
                (mock, context) -> {
                    // connect() is void; no stubbing required for playback.
                })) {

            controller().playAudio(CALL_SESSION_ID, LEG_ID, AUDIO_PATH);

            EslClient eslMock = esl.constructed().get(0);
            verify(eslMock).connect();
            verify(eslMock).playFile(CHANNEL_UUID, AUDIO_PATH);
            verify(eslMock, never()).hangup(anyString());
        }
    }

    @Test
    @DisplayName("P9: playback targets the provider call id resolved from the call session")
    void playbackTargetsResolvedChannelUuid() {
        // providerCallId is the originate-returned FreeSWITCH UUID; the
        // adapter must use exactly that as the command target.
        when(callSessionRepository.findById(CALL_SESSION_ID))
                .thenReturn(Optional.of(sessionWithProviderCallId()));

        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class,
                (mock, context) -> {
                    // connect() is void; no stubbing required for playback.
                })) {

            controller().playAudio(CALL_SESSION_ID, LEG_ID, AUDIO_PATH);

            verify(esl.constructed().get(0)).playFile(CHANNEL_UUID, AUDIO_PATH);
        }
    }

    @Test
    @DisplayName("playAudio with a blank audio URI is rejected before any ESL command")
    void playAudioRejectsBlankUri() {
        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class)) {
            assertThatThrownBy(() -> controller().playAudio(CALL_SESSION_ID, LEG_ID, " "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Audio URI is required");

            assertThat(esl.constructed()).isEmpty();
        }
    }

    @Test
    @DisplayName("playAudio fails fast when the call session has no provider call id")
    void playAudioFailsWithoutProviderCallId() {
        CallSession session = sessionWithProviderCallId();
        session.setProviderCallId("");
        when(callSessionRepository.findById(CALL_SESSION_ID)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> controller().playAudio(CALL_SESSION_ID, LEG_ID, AUDIO_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No provider call id");
    }

    @Test
    @DisplayName("playback command failure propagates as EslException (caller records failure)")
    void playAudioPropagatesEslException() {
        when(callSessionRepository.findById(CALL_SESSION_ID))
                .thenReturn(Optional.of(sessionWithProviderCallId()));

        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class,
                (mock, context) -> {
                    org.mockito.Mockito.doThrow(new EslException("503 breakout"))
                            .when(mock).playFile(anyString(), anyString());
                })) {

            assertThatThrownBy(() -> controller().playAudio(CALL_SESSION_ID, LEG_ID, AUDIO_PATH))
                    .isInstanceOf(EslException.class)
                    .hasMessageContaining("503");
        }
    }

    @Test
    @DisplayName("terminateCall issues uuid_kill against the provider call id")
    void terminateCallIssuesUuidKill() {
        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class,
                (mock, context) -> {
                    // connect() is void; no stubbing required for hangup.
                })) {

            controller().terminateCall(CALL_SESSION_ID, CHANNEL_UUID);

            EslClient eslMock = esl.constructed().get(0);
            verify(eslMock).connect();
            verify(eslMock).hangup(CHANNEL_UUID);
            verify(eslMock, never()).playFile(anyString(), anyString());
        }
    }

    @Test
    @DisplayName("terminateCall resolves the channel from the session when provider id is absent")
    void terminateCallResolvesFromSession() {
        when(callSessionRepository.findById(CALL_SESSION_ID))
                .thenReturn(Optional.of(sessionWithProviderCallId()));

        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class,
                (mock, context) -> {
                    // connect() is void; no stubbing required for hangup.
                })) {

            controller().terminateCall(CALL_SESSION_ID, null);

            verify(esl.constructed().get(0)).hangup(CHANNEL_UUID);
        }
    }

    @Test
    @DisplayName("stopPlayback is an explicit no-op in VB-1 (no stray ESL commands)")
    void stopPlaybackIsNoOp() {
        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class)) {

            controller().stopPlayback(CALL_SESSION_ID, LEG_ID);

            assertThat(esl.constructed()).isEmpty();
        }
    }

    @Test
    @DisplayName("Non-VB-1/2/3 features (DTMF collection, recording) are explicitly unsupported")
    void laterPhaseOperationsUnsupported() {
        FreeSwitchVoiceMediaController controller = controller();

        assertThatThrownBy(() -> controller.collectDtmf(CALL_SESSION_ID, LEG_ID, 1, "#", 5))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("VB-1");
        // bridge() is supported as of VB-3 (tested in AgentLegDialerContractTest
        // / ConnectByAgentServiceTest); recording remains future-phase.
        assertThatThrownBy(() -> controller.startRecording(CALL_SESSION_ID, LEG_ID, "wav"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> controller.stopRecording(CALL_SESSION_ID, LEG_ID))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
