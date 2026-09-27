package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.CallAttempt;
import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.dtmf.DtmfResultService;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VB-3 unit tests: the EslEventService agent-leg branch — events whose
 * Call-UUID matches an AGENT-type CallLeg (never an attempt) are routed to
 * the AgentConnectEvents boundary, CHANNEL_BRIDGE is confirmed via
 * Bridge-B-Unique-ID, and unknown UUIDs are ignored safely
 * (spec §25/§27/§43).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentEslRoutingTest {

    private static final UUID TENANT = UUID.fromString("5a000000-0000-4000-8000-0000000000a1");
    private static final UUID SESSION = UUID.fromString("5e000000-0000-4000-8000-0000000000e1");
    private static final UUID AGENT_LEG_ID = UUID.fromString("57000000-0000-4000-8000-0000000000b1");
    private static final String AGENT_UUID = "agent-fs-uuid";
    private static final String CUSTOMER_UUID = "customer-fs-uuid";

    @Mock
    private CallAttemptRepository attemptRepository;

    @Mock
    private CallSessionRepository callSessionRepository;

    @Mock
    private CallLegRepository callLegRepository;

    @Mock
    private VoiceCapacityService voiceCapacity;

    @Mock
    private VoiceMediaController mediaController;

    @Mock
    private DtmfResultService dtmfResultService;

    @Mock
    private com.shivang.obd.voice.agent.AgentConnectEvents agentConnectEvents;

    private EslEventService service;

    @BeforeEach
    void setUp() {
        service = new EslEventService(attemptRepository, callSessionRepository,
                callLegRepository, voiceCapacity, mediaController,
                List.of(), Optional.empty(), dtmfResultService,
                Optional.of(agentConnectEvents), java.util.Optional.empty(), java.util.Optional.empty());
    }

    private EslEvent event(String name, String callUuid) {
        EslEvent e = new EslEvent(name);
        e.addHeader("Call-UUID", callUuid);
        return e;
    }

    private CallLeg agentLeg() {
        CallLeg leg = new CallLeg();
        leg.setId(AGENT_LEG_ID);
        leg.setCallSessionId(SESSION);
        leg.setLegType(CallLegType.AGENT);
        leg.setStatus(CallLegStatus.DIALING);
        leg.setProviderCallId(AGENT_UUID);
        return leg;
    }

    @Test
    @DisplayName("A1: CHANNEL_ANSWER on the agent UUID reaches the connect boundary, not the attempt path")
    void agentAnswerRoutedToConnectBoundary() {
        CallLeg leg = agentLeg();
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.empty());
        when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.of(leg));

        boolean processed = service.processEvent(event("CHANNEL_ANSWER", AGENT_UUID));

        assertThat(processed).isTrue();
        verify(agentConnectEvents).onAgentLegAnswered(leg);
        verify(attemptRepository, never()).findByProviderCallIdAndDeletedAtIsNull(anyString());
    }

    @Test
    @DisplayName("A2: agent CHANNEL_PROGRESS → onAgentLegRinging")
    void agentProgressRouted() {
        CallLeg leg = agentLeg();
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.empty());
        when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.of(leg));

        service.processEvent(event("CHANNEL_PROGRESS", AGENT_UUID));

        verify(agentConnectEvents).onAgentLegRinging(leg);
    }

    @Test
    @DisplayName("A3: agent CHANNEL_HANGUP carries the hangup cause to the connect boundary")
    void agentHangupRouted() {
        CallLeg leg = agentLeg();
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.empty());
        when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.of(leg));
        EslEvent e = event("CHANNEL_HANGUP", AGENT_UUID);
        e.addHeader("Hangup-Cause", "NORMAL_CLEARING");

        service.processEvent(e);

        verify(agentConnectEvents).onAgentLegHangup(leg, "NORMAL_CLEARING");
    }

    @Test
    @DisplayName("A4: CHANNEL_BRIDGE with Bridge-B-Unique-ID = agent UUID → onBridgeConfirmed")
    void bridgeConfirmedRouted() {
        CallLeg leg = agentLeg();
        when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.of(leg));
        EslEvent e = event("CHANNEL_BRIDGE", CUSTOMER_UUID);
        e.addHeader("Bridge-B-Unique-ID", AGENT_UUID);

        boolean processed = service.processEvent(e);

        assertThat(processed).isTrue();
        verify(agentConnectEvents).onBridgeConfirmed(SESSION, AGENT_UUID);
    }

    @Test
    @DisplayName("A5: CHANNEL_BRIDGE without an agent B-UUID is ignored")
    void bridgeWithoutAgentIgnored() {
        when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull("some-other-uuid"))
                .thenReturn(Optional.empty());
        EslEvent e = event("CHANNEL_BRIDGE", CUSTOMER_UUID);
        e.addHeader("Bridge-B-Unique-ID", "some-other-uuid");

        assertThat(service.processEvent(e)).isFalse();
        verify(agentConnectEvents, never()).onBridgeConfirmed(any(), anyString());
    }

    @Test
    @DisplayName("A6: unknown agent UUID is ignored safely (fail closed)")
    void unknownAgentUuidIgnored() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull("unknown-uuid"))
                .thenReturn(Optional.empty());
        when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull("unknown-uuid"))
                .thenReturn(Optional.empty());

        assertThat(service.processEvent(event("CHANNEL_ANSWER", "unknown-uuid"))).isFalse();
        verify(agentConnectEvents, never()).onAgentLegAnswered(any());
    }

    @Test
    @DisplayName("A7: customer-leg hangup triggers agent cleanup (caller hangup during connect)")
    void customerHangupTriggersAgentCleanup() {
        CallSession session = new CallSession();
        session.setId(SESSION);
        session.setTenantId(TENANT);
        session.setStatus(com.shivang.obd.voice.call.CallSessionStatus.CONNECTING_AGENT);
        CallAttempt attempt = new CallAttempt();
        attempt.setId(UUID.fromString("5f000000-0000-4000-8000-0000000000f1"));
        attempt.setTenantId(TENANT);
        attempt.setStatus(com.shivang.obd.campaign.CallAttemptStatus.IN_PROGRESS);
        CallLeg customerLeg = new CallLeg();
        customerLeg.setId(UUID.fromString("58000000-0000-4000-8000-0000000000c1"));
        customerLeg.setCallSessionId(SESSION);
        customerLeg.setLegType(CallLegType.CUSTOMER);
        customerLeg.setStatus(CallLegStatus.ANSWERED);
        customerLeg.setProviderCallId(CUSTOMER_UUID);

        when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(CUSTOMER_UUID))
                .thenReturn(Optional.of(attempt));
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CUSTOMER_UUID))
                .thenReturn(Optional.of(session));
        when(callLegRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION))
                .thenReturn(List.of(customerLeg));
        EslEvent e = event("CHANNEL_HANGUP", CUSTOMER_UUID);
        e.addHeader("Hangup-Cause", "NORMAL_CLEARING");

        service.processEvent(e);

        verify(agentConnectEvents).onCallerHangup(SESSION);
    }

    @Test
    @DisplayName("A8: connect-boundary failure never breaks event processing (contained)")
    void boundaryFailureContained() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.empty());
        when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.of(agentLeg()));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(agentConnectEvents).onAgentLegAnswered(any());

        boolean processed = service.processEvent(event("CHANNEL_ANSWER", AGENT_UUID));

        assertThat(processed).isTrue();
    }

    @Test
    @DisplayName("A9: agent events are contained even when the leg lookup mutates state")
    void agentEventsDoNotTouchAttempts() {
        CallLeg leg = agentLeg();
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.empty());
        when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.of(leg));

        service.processEvent(event("CHANNEL_ANSWER", AGENT_UUID));

        verify(attemptRepository, never()).findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID);
    }
}
