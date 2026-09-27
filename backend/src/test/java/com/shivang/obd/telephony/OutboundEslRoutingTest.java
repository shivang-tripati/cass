package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallType;
import com.shivang.obd.voice.dtmf.DtmfResultService;
import com.shivang.obd.voice.media.VoiceMediaController;
import com.shivang.obd.voice.outbound.AgentOutboundCallService;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
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
 * VB-4E ESL contract tests: customer-leg events on attempt-less
 * {@code CONTACT_CENTER_OUTBOUND} sessions are routed to the outbound
 * boundary (progress/answer/bridge/hangup), other session types are
 * untouched, unknown UUIDs are ignored safely, and boundary exceptions
 * are contained so the shared event loop never breaks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboundEslRoutingTest {

    private static final UUID TENANT = UUID.fromString("ec000000-0000-4000-8000-0000000000e1");
    private static final UUID SESSION = UUID.fromString("ec000000-0000-4000-8000-0000000000a1");
    private static final String CUSTOMER_UUID = "customer-fs-uuid";
    private static final String AGENT_UUID = "agent-fs-uuid";

    @Mock private CallAttemptRepository attemptRepository;
    @Mock private CallSessionRepository callSessionRepository;
    @Mock private CallLegRepository callLegRepository;
    @Mock private VoiceCapacityService voiceCapacity;
    @Mock private VoiceMediaController mediaController;
    @Mock private DtmfResultService dtmfResultService;
    @Mock private AgentOutboundCallService outboundService;
    @Mock private com.shivang.obd.voice.agent.AgentConnectEvents agentConnectEvents;

    private EslEventService service;

    @BeforeEach
    void setUp() {
        service = new EslEventService(attemptRepository, callSessionRepository,
                callLegRepository, voiceCapacity, mediaController,
                List.of(), Optional.empty(), dtmfResultService,
                Optional.of(agentConnectEvents), Optional.empty(), Optional.of(outboundService));
    }

    private EslEvent event(String name, String callUuid) {
        EslEvent e = new EslEvent(name);
        e.addHeader("Call-UUID", callUuid);
        return e;
    }

    private CallSession outboundSession() {
        CallSession s = new CallSession();
        s.setId(SESSION);
        s.setTenantId(TENANT);
        s.setCallType(CallType.CONTACT_CENTER_OUTBOUND);
        s.setProviderCallId(CUSTOMER_UUID);
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CUSTOMER_UUID))
                .thenReturn(Optional.of(s));
        return s;
    }

    private CallLeg customerLeg(CallLegStatus status) {
        CallLeg leg = new CallLeg();
        leg.setId(UUID.randomUUID());
        leg.setCallSessionId(SESSION);
        leg.setLegType(CallLegType.CUSTOMER);
        leg.setStatus(status);
        leg.setProviderCallId(CUSTOMER_UUID);
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                SESSION, CallLegType.CUSTOMER)).thenReturn(List.of(leg));
        return leg;
    }

    @Test
    @DisplayName("OB1: CHANNEL_PROGRESS on outbound session → onCustomerLegRinging")
    void progressRouted() {
        outboundSession();
        CallLeg leg = customerLeg(CallLegStatus.DIALING);

        boolean processed = service.processEvent(event("CHANNEL_PROGRESS", CUSTOMER_UUID));

        assertThat(processed).isTrue();
        verify(outboundService).onCustomerLegRinging(leg);
    }

    @Test
    @DisplayName("OB2: CHANNEL_ANSWER on outbound session → onCustomerLegAnswered")
    void answerRouted() {
        outboundSession();
        CallLeg leg = customerLeg(CallLegStatus.RINGING);

        boolean processed = service.processEvent(event("CHANNEL_ANSWER", CUSTOMER_UUID));

        assertThat(processed).isTrue();
        verify(outboundService).onCustomerLegAnswered(leg);
    }

    @Test
    @DisplayName("OB3: CHANNEL_HANGUP on outbound session → onCustomerLegHangup with tenant + cause")
    void hangupRouted() {
        outboundSession();
        CallLeg leg = customerLeg(CallLegStatus.BRIDGED);
        EslEvent e = event("CHANNEL_HANGUP", CUSTOMER_UUID);
        e.addHeader("Hangup-Cause", "NORMAL_CLEARING");

        boolean processed = service.processEvent(e);

        assertThat(processed).isTrue();
        verify(outboundService).onCustomerLegHangup(leg, "NORMAL_CLEARING", TENANT);
    }

    @Test
    @DisplayName("OB4: CHANNEL_BRIDGE anchored on the customer channel → shared VB-3 correlation")
    void bridgeRoutedToSharedHandler() {
        CallSession session = outboundSession();
        session.setStatus(com.shivang.obd.voice.call.CallSessionStatus.CONNECTING_AGENT);
        CallLeg agentLeg = new CallLeg();
        agentLeg.setId(UUID.randomUUID());
        agentLeg.setCallSessionId(SESSION);
        agentLeg.setLegType(CallLegType.AGENT);
        agentLeg.setProviderCallId(AGENT_UUID);
        when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull(AGENT_UUID))
                .thenReturn(Optional.of(agentLeg));
        customerLeg(CallLegStatus.ANSWERED);
        EslEvent e = event("CHANNEL_BRIDGE", CUSTOMER_UUID);
        e.addHeader("Bridge-B-Unique-ID", AGENT_UUID);

        boolean processed = service.processEvent(e);

        assertThat(processed).isTrue();
        // The shared VB-3 boundary confirmed the bridge (session was CONNECTING_AGENT).
        verify(agentConnectEvents).onBridgeConfirmed(SESSION, AGENT_UUID);
        verify(outboundService, never()).onCustomerLegAnswered(any());
    }

    @Test
    @DisplayName("OB5: outbound session events are NOT routed when no attempt exists but type differs (campaign sessions untouched)")
    void campaignSessionEventsUnaffected() {
        CallSession campaign = new CallSession();
        campaign.setId(SESSION);
        campaign.setCallType(CallType.VOICE_BLAST);
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CUSTOMER_UUID))
                .thenReturn(Optional.of(campaign));
        when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(CUSTOMER_UUID))
                .thenReturn(Optional.empty());
        customerLeg(CallLegStatus.DIALING);

        boolean processed = service.processEvent(event("CHANNEL_ANSWER", CUSTOMER_UUID));

        assertThat(processed).isFalse();
        verify(outboundService, never()).onCustomerLegAnswered(any());
    }

    @Test
    @DisplayName("OB6: unknown UUID ignored safely (no session, no attempt, no agent leg)")
    void unknownUuidIgnored() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull("unknown-uuid"))
                .thenReturn(Optional.empty());
        when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull("unknown-uuid"))
                .thenReturn(Optional.empty());
        when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull("unknown-uuid"))
                .thenReturn(Optional.empty());

        assertThat(service.processEvent(event("CHANNEL_ANSWER", "unknown-uuid"))).isFalse();
        verify(outboundService, never()).onCustomerLegAnswered(any());
    }

    @Test
    @DisplayName("OB7: duplicate ANSWER events — boundary idempotency keeps state consistent")
    void duplicateEventsIdempotent() {
        outboundSession();
        CallLeg leg = customerLeg(CallLegStatus.ANSWERED); // already answered

        service.processEvent(event("CHANNEL_ANSWER", CUSTOMER_UUID));
        service.processEvent(event("CHANNEL_ANSWER", CUSTOMER_UUID));

        // Both events were delivered; the boundary's idempotency guard
        // means the second is a no-op (verified by the state assertions
        // in AgentOutboundCallServiceTest O16).
        verify(outboundService, org.mockito.Mockito.times(2)).onCustomerLegAnswered(leg);
        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.ANSWERED);
    }

    @Test
    @DisplayName("OB8: boundary exception is contained — the shared event loop never breaks")
    void boundaryExceptionContained() {
        outboundSession();
        customerLeg(CallLegStatus.RINGING);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(outboundService).onCustomerLegAnswered(any());

        // The event WAS routed to the boundary; the exception is contained so
        // the shared loop never breaks (same semantics as the inbound branch).
        assertThat(service.processEvent(event("CHANNEL_ANSWER", CUSTOMER_UUID))).isTrue();
    }
}
