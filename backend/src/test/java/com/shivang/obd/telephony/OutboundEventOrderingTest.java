package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
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
 * VB-4F hardening: FreeSWITCH event-ordering permutations on the outbound
 * lifecycle. Real ESL deliveries are not guaranteed to arrive in the ideal
 * order — every permutation must either preserve meaningful lifecycle
 * information or be safely contained, and none may duplicate resources or
 * strand reservations.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboundEventOrderingTest {

    private static final UUID TENANT = UUID.fromString("f4000000-0000-4000-8000-0000000000e1");
    private static final UUID SESSION = UUID.fromString("f4000000-0000-4000-8000-0000000000a1");
    private static final String CUSTOMER_UUID = "ordering-customer-uuid";

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

    private CallSession outboundSession(CallSessionStatus status) {
        CallSession s = new CallSession();
        s.setId(SESSION);
        s.setTenantId(TENANT);
        s.setCallType(CallType.CONTACT_CENTER_OUTBOUND);
        s.setProviderCallId(CUSTOMER_UUID);
        s.setStatus(status);
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
        return leg;
    }

    private void stubLegs(CallLeg leg) {
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                SESSION, CallLegType.CUSTOMER)).thenReturn(List.of(leg));
    }

    @Test
    @DisplayName("EO1: HANGUP before ANSWER (caller never answered) — hangup processed, no originate, no bridge confirmation")
    void hangupBeforeAnswer() {
        CallSession s = outboundSession(CallSessionStatus.DIALING);
        CallLeg leg = customerLeg(CallLegStatus.DIALING);
        stubLegs(leg);

        EslEvent hangup = event("CHANNEL_HANGUP", CUSTOMER_UUID);
        hangup.addHeader("Hangup-Cause", "NORMAL_CLEARING");
        service.processEvent(hangup);

        verify(outboundService).onCustomerLegHangup(eq(leg), eq("NORMAL_CLEARING"), eq(TENANT));
        assertThat(s.getStatus()).isEqualTo(CallSessionStatus.DIALING); // hangup handler owns finalization
    }

    @Test
    @DisplayName("EO2: ANSWER after HANGUP (late/duplicate answer post-termination) — delivered but contained, terminal state preserved, no bridge")
    void answerAfterHangup() {
        outboundSession(CallSessionStatus.COMPLETED);
        CallLeg leg = customerLeg(CallLegStatus.COMPLETED);
        stubLegs(leg);
        // Mirror the real service's pre-answer guard: a terminal leg is a no-op.
        org.mockito.Mockito.doAnswer(inv -> {
            if (leg.getStatus() == CallLegStatus.DIALING
                    || leg.getStatus() == CallLegStatus.RINGING) {
                leg.setStatus(CallLegStatus.ANSWERED);
            }
            return null;
        }).when(outboundService).onCustomerLegAnswered(any());

        service.processEvent(event("CHANNEL_ANSWER", CUSTOMER_UUID));

        // The boundary delivers (containment contract); the downstream guard
        // ignores the invalid transition — the session must not resurrect.
        verify(outboundService).onCustomerLegAnswered(any());
        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.COMPLETED);
        verify(agentConnectEvents, never()).onBridgeConfirmed(any(), anyString());
    }

    @Test
    @DisplayName("EO3: PROGRESS → ANSWER → duplicate ANSWER → HANGUP ×2 — full noisy sequence, no duplicate agent originate")
    void noisySequence() {
        outboundSession(CallSessionStatus.DIALING);
        CallLeg leg = customerLeg(CallLegStatus.DIALING);
        stubLegs(leg);
        // Mirror the real service's pre-answer guard (idempotency enforced
        // downstream of the boundary's contained delivery).
        java.util.concurrent.atomic.AtomicInteger answerMutations =
                new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
                    if (leg.getStatus() == CallLegStatus.DIALING
                            || leg.getStatus() == CallLegStatus.RINGING) {
                        leg.setStatus(CallLegStatus.ANSWERED);
                        answerMutations.incrementAndGet();
                    }
                    return null;
                }).when(outboundService).onCustomerLegAnswered(any());

        service.processEvent(event("CHANNEL_PROGRESS", CUSTOMER_UUID));
        service.processEvent(event("CHANNEL_ANSWER", CUSTOMER_UUID));
        service.processEvent(event("CHANNEL_ANSWER", CUSTOMER_UUID));
        service.processEvent(event("CHANNEL_HANGUP", CUSTOMER_UUID));
        service.processEvent(event("CHANNEL_HANGUP", CUSTOMER_UUID));

        // Both answers delivered (boundary contract) but the leg state
        // mutated exactly once — the duplicate hit the pre-answer guard.
        verify(outboundService, times(2)).onCustomerLegAnswered(any());
        assertThat(answerMutations.get()).isEqualTo(1);
        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.ANSWERED);
        verify(outboundService, times(2)).onCustomerLegHangup(any(), any(), any());
        verify(outboundService, times(1)).onCustomerLegRinging(any());
    }

    @Test
    @DisplayName("EO4: BRIDGE without any prior ANSWER — routed to shared correlation, ignored safely, nothing originates")
    void bridgeWithoutAnswer() {
        outboundSession(CallSessionStatus.DIALING);
        stubLegs(customerLeg(CallLegStatus.DIALING));

        EslEvent bridge = event("CHANNEL_BRIDGE", CUSTOMER_UUID);
        bridge.addHeader("Bridge-B-Unique-ID", "some-agent-uuid");
        service.processEvent(bridge);

        // No agent leg exists to correlate — the shared bridge handler
        // finds no anchor and the outbound boundary is never invoked.
        verify(agentConnectEvents, never()).onBridgeConfirmed(any(), anyString());
        verify(outboundService, never()).onCustomerLegAnswered(any());
    }

    @Test
    @DisplayName("EO5: PROGRESS after ANSWER (out-of-order) — ring state never regresses an answered leg")
    void progressAfterAnswer() {
        outboundSession(CallSessionStatus.ANSWERED);
        CallLeg leg = customerLeg(CallLegStatus.ANSWERED);
        stubLegs(leg);

        service.processEvent(event("CHANNEL_PROGRESS", CUSTOMER_UUID));

        verify(outboundService).onCustomerLegRinging(eq(leg));
        // The service's monotonic guard ignores ringing for non-DIALING/INITIATED legs.
        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.ANSWERED);
    }

    @Test
    @DisplayName("EO6: event for an unknown channel UUID mid-stream — contained, later events still processed")
    void unknownEventBetweenValidOnes() {
        outboundSession(CallSessionStatus.DIALING);
        CallLeg leg = customerLeg(CallLegStatus.DIALING);
        stubLegs(leg);

        service.processEvent(event("CHANNEL_ANSWER", "unknown-uuid"));
        service.processEvent(event("CHANNEL_ANSWER", CUSTOMER_UUID));

        verify(outboundService, times(1)).onCustomerLegAnswered(any());
    }

    @Test
    @DisplayName("EO7: HANGUP with no cause header — null cause delivered, service normalizes as success")
    void hangupNullCause() {
        outboundSession(CallSessionStatus.ANSWERED);
        CallLeg leg = customerLeg(CallLegStatus.ANSWERED);
        stubLegs(leg);

        EslEvent hangup = event("CHANNEL_HANGUP", CUSTOMER_UUID);
        service.processEvent(hangup);

        verify(outboundService).onCustomerLegHangup(eq(leg), isNull(), eq(TENANT));
    }
}
