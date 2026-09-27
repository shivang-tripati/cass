package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
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
 * VB-3 FreeSWITCH contract tests: the agent originate adapter and the
 * bridge command. The important contract is the actual ESL operation and
 * the channel UUIDs used — not just "the boundary method was called"
 * (spec §43).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentLegDialerContractTest {

    private static final String CALLER_ID = "+15550001111";
    private static final String AGENT_TARGET = "sip:agent1@example.test";
    private static final String AGENT_UUID = "agent-originate-uuid";
    private static final String GATEWAY = "gw-internal";

    @Mock
    private FreeSwitchProperties properties;

    @Mock
    private CallSessionRepository callSessionRepository;

    @Mock
    private CallLegRepository callLegRepository;

    @Test
    @DisplayName("F1: agent originate goes through the shared EslClient.originate mechanism")
    void originateUsesSharedEslClient() {
        FreeSwitchAgentLegDialer dialer = new FreeSwitchAgentLegDialer(properties);

        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class,
                (mock, context) -> {
                    org.mockito.Mockito.when(mock.originate(CALLER_ID, AGENT_TARGET, GATEWAY, "external"))
                            .thenReturn(AGENT_UUID);
                })) {

            String uuid = dialer.originateAgentLeg(CALLER_ID, AGENT_TARGET, GATEWAY, "external");

            assertThat(uuid).isEqualTo(AGENT_UUID);
            EslClient eslMock = esl.constructed().get(0);
            verify(eslMock).connect();
            verify(eslMock).originate(CALLER_ID, AGENT_TARGET, GATEWAY, "external");
        }
    }

    @Test
    @DisplayName("F2: originate failure propagates as EslException (caller releases the reservation)")
    void originateFailurePropagates() {
        FreeSwitchAgentLegDialer dialer = new FreeSwitchAgentLegDialer(properties);

        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class,
                (mock, context) -> {
                    org.mockito.Mockito.when(mock.originate(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                            .thenThrow(new EslException("originate rejected: NO_ROUTE"));
                })) {

            assertThatThrownBy(() -> dialer.originateAgentLeg(CALLER_ID, AGENT_TARGET, GATEWAY, null))
                    .isInstanceOf(EslException.class)
                    .hasMessageContaining("NO_ROUTE");
        }
    }

    @Test
    @DisplayName("F3: blank dial target is rejected before any ESL connection")
    void blankTargetRejected() {
        FreeSwitchAgentLegDialer dialer = new FreeSwitchAgentLegDialer(properties);

        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class)) {
            assertThatThrownBy(() -> dialer.originateAgentLeg(CALLER_ID, " ", GATEWAY, null))
                    .isInstanceOf(EslException.class)
                    .hasMessageContaining("dial target");
            assertThat(esl.constructed()).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // FreeSwitchVoiceMediaController.bridge — ESL command contract
    // ------------------------------------------------------------------

    private static final UUID SESSION_ID =
            UUID.fromString("7e000000-0000-4000-8000-0000000000e1");
    private static final String CALLER_UUID = "caller-fs-uuid";
    private static final String AGENT_FS_UUID = "agent-fs-uuid";

    private CallLeg leg(CallLegType type, String uuid) {
        CallLeg leg = new CallLeg();
        leg.setId(UUID.randomUUID());
        leg.setCallSessionId(SESSION_ID);
        leg.setLegType(type);
        leg.setStatus(CallLegStatus.ANSWERED);
        leg.setProviderCallId(uuid);
        return leg;
    }

    @Test
    @DisplayName("F4: bridge issues uuid_bridge <caller> <agent> with both leg UUIDs")
    void bridgeIssuesUuidBridgeWithLegUuids() {
        CallLeg callerLeg = leg(CallLegType.CUSTOMER, CALLER_UUID);
        CallLeg agentLeg = leg(CallLegType.AGENT, AGENT_FS_UUID);
        when(callLegRepository.findByIdAndDeletedAtIsNull(callerLeg.getId()))
                .thenReturn(Optional.of(callerLeg));
        when(callLegRepository.findByIdAndDeletedAtIsNull(agentLeg.getId()))
                .thenReturn(Optional.of(agentLeg));
        FreeSwitchVoiceMediaController controller =
                new FreeSwitchVoiceMediaController(properties, callSessionRepository, callLegRepository);

        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class)) {
            controller.bridge(SESSION_ID, callerLeg.getId(), agentLeg.getId());

            EslClient eslMock = esl.constructed().get(0);
            verify(eslMock).connect();
            verify(eslMock).bridge(CALLER_UUID, AGENT_FS_UUID);
        }
    }

    @Test
    @DisplayName("F5: bridge rejects legs from a different call session (fail closed)")
    void bridgeRejectsForeignLegs() {
        CallLeg callerLeg = leg(CallLegType.CUSTOMER, CALLER_UUID);
        CallLeg agentLeg = leg(CallLegType.AGENT, AGENT_FS_UUID);
        agentLeg.setCallSessionId(UUID.fromString("7f000000-0000-4000-8000-0000000000f1"));
        when(callLegRepository.findByIdAndDeletedAtIsNull(callerLeg.getId()))
                .thenReturn(Optional.of(callerLeg));
        when(callLegRepository.findByIdAndDeletedAtIsNull(agentLeg.getId()))
                .thenReturn(Optional.of(agentLeg));
        FreeSwitchVoiceMediaController controller =
                new FreeSwitchVoiceMediaController(properties, callSessionRepository, callLegRepository);

        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class)) {
            assertThatThrownBy(() -> controller.bridge(SESSION_ID, callerLeg.getId(), agentLeg.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("do not belong");
            assertThat(esl.constructed()).isEmpty();
        }
    }

    @Test
    @DisplayName("F6: bridge without provider UUIDs on both legs is rejected before ESL")
    void bridgeRequiresProviderUuids() {
        CallLeg callerLeg = leg(CallLegType.CUSTOMER, CALLER_UUID);
        CallLeg agentLeg = leg(CallLegType.AGENT, null);
        when(callLegRepository.findByIdAndDeletedAtIsNull(callerLeg.getId()))
                .thenReturn(Optional.of(callerLeg));
        when(callLegRepository.findByIdAndDeletedAtIsNull(agentLeg.getId()))
                .thenReturn(Optional.of(agentLeg));
        FreeSwitchVoiceMediaController controller =
                new FreeSwitchVoiceMediaController(properties, callSessionRepository, callLegRepository);

        try (MockedConstruction<EslClient> esl = mockConstruction(EslClient.class)) {
            assertThatThrownBy(() -> controller.bridge(SESSION_ID, callerLeg.getId(), agentLeg.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("provider call ids");
            assertThat(esl.constructed()).isEmpty();
        }
    }

    @Test
    @DisplayName("F7: EslClient.bridge validates the ESL +OK response and rejects -ERR")
    void eslClientBridgeResponseValidation() throws Exception {
        FreeSwitchProperties props = new FreeSwitchProperties();
        props.setEnabled(true);
        props.setHost("127.0.0.1");
        props.setPort(8021);
        props.setPassword("ClueCon");
        EslClient client = new EslClient(props);

        // Inject faked socket streams — a real ESL connection is not needed to
        // assert the command/response contract.
        var out = new java.io.ByteArrayOutputStream();
        var writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(out), true);
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.ByteArrayInputStream("+OK accepted\n\n".getBytes())));
        org.springframework.test.util.ReflectionTestUtils.setField(client, "authenticated", true);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "writer", writer);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "reader", reader);

        client.bridge("caller-uuid", "agent-uuid");

        assertThat(out.toString()).contains("uuid_bridge caller-uuid agent-uuid");

        // Error path: -ERR must raise EslException.
        var readerErr = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.ByteArrayInputStream("-ERR NO_ANSWER\n\n".getBytes())));
        org.springframework.test.util.ReflectionTestUtils.setField(client, "reader", readerErr);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.bridge("a", "b"))
                .isInstanceOf(EslException.class)
                .hasMessageContaining("bridge");
    }
}
