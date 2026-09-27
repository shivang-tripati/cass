package com.shivang.obd.voice.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.ConnectByAgentService;

import com.shivang.obd.voice.call.CallDirection;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.call.EndpointType;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * VB-3 unit tests: the CONNECT_BY_AGENT connect flow and agent-leg event
 * lifecycle driven through the {@code AgentConnectEvents} boundary —
 * atomic reservation, agent leg creation, originate, answer → bridge
 * request, bridge confirmation, and every failure/hangup cleanup path
 * (spec §14/§16/§17/§20/§23/§24/§25/§26/§27/§46).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectByAgentServiceTest {

    private static final UUID TENANT = UUID.fromString("4a000000-0000-4000-8000-0000000000a1");
    private static final UUID AGENT = UUID.fromString("4c000000-0000-4000-8000-0000000000c1");
    private static final UUID SESSION = UUID.fromString("4e000000-0000-4000-8000-0000000000e1");
    private static final UUID ATTEMPT = UUID.fromString("4f000000-0000-4000-8000-0000000000f1");
    private static final UUID DID = UUID.fromString("49000000-0000-4000-8000-0000000000d1");
    private static final String CALLER_UUID = "caller-channel-uuid";
    private static final String AGENT_UUID = "agent-channel-uuid";

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AgentEndpointRepository endpointRepository;

    @Mock
    private AgentReservationService reservationService;

    @Mock
    private CallSessionRepository callSessionRepository;

    @Mock
    private CallLegRepository callLegRepository;

    @Mock
    private AgentLegDialer agentLegDialer;

    @Mock
    private VoiceMediaController mediaController;

    @Mock
    private com.shivang.obd.did.DidRepository didRepository;

    private ConnectByAgentService service;

    @BeforeEach
    void setUp() {
        service = new ConnectByAgentService(agentRepository, endpointRepository,
                reservationService, callSessionRepository, callLegRepository,
                agentLegDialer, mediaController, didRepository);
    }

    @BeforeEach
    void stubDid() {
        var did = new com.shivang.obd.did.DidEntity();
        did.setId(DID);
        did.setE164Number("+15550001111");
        when(didRepository.findByIdAndDeletedAtIsNull(DID)).thenReturn(Optional.of(did));
    }

    // --- fixtures -------------------------------------------------------

    private CallSession sessionIn(CallSessionStatus status) {
        CallSession s = new CallSession();
        s.setId(SESSION);
        s.setTenantId(TENANT);
        s.setStatus(status);
        s.setProviderCallId(CALLER_UUID);
        s.setDidId(DID);
        return s;
    }

    private CallLeg callerLeg(CallLegStatus status) {
        CallLeg l = new CallLeg();
        l.setId(UUID.fromString("45000000-0000-4000-8000-0000000000c1"));
        l.setCallSessionId(SESSION);
        l.setLegType(CallLegType.CUSTOMER);
        l.setStatus(status);
        l.setProviderCallId(CALLER_UUID);
        return l;
    }

    private Agent eligibleAgent() {
        Agent a = new Agent();
        a.setId(AGENT);
        a.setTenantId(TENANT);
        a.setDisplayName("Agent One");
        a.setAdminStatus(AgentAdminStatus.ACTIVE);
        a.setAvailability(AgentAvailability.AVAILABLE);
        a.setMaxConcurrentCalls(1);
        return a;
    }

    private AgentEndpointEntity sipEndpoint() {
        AgentEndpointEntity e = new AgentEndpointEntity();
        e.setId(UUID.fromString("46000000-0000-4000-8000-0000000000d1"));
        e.setAgentId(AGENT);
        e.setTenantId(TENANT);
        e.setEndpointType(EndpointType.SIP);
        e.setDialTarget("sip:agent1@example.test");
        e.setEnabled(true);
        return e;
    }

    private CallLeg agentLeg(CallLegStatus status) {
        CallLeg l = new CallLeg();
        l.setId(UUID.fromString("47000000-0000-4000-8000-0000000000b1"));
        l.setCallSessionId(SESSION);
        l.setLegType(CallLegType.AGENT);
        l.setAgentId(AGENT);
        l.setStatus(status);
        l.setProviderCallId(AGENT_UUID);
        l.setInitiatedAt(java.time.Instant.now());
        return l;
    }

    private void stubHappySelection() {
        when(agentRepository.findEligibleOrdered(eq(TENANT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(eligibleAgent())));
        when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                eq(AGENT), eq(TENANT))).thenReturn(List.of(sipEndpoint()));
        when(reservationService.reserve(eq(AGENT), eq(TENANT), eq(SESSION), any()))
                .thenReturn(Optional.of(new AgentReservation()));
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(sessionIn(CallSessionStatus.WAITING_FOR_DTMF)));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(SESSION, CallLegType.CUSTOMER))
                .thenReturn(List.of(callerLeg(CallLegStatus.ANSWERED)));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(SESSION, CallLegType.AGENT))
                .thenReturn(List.of());
        when(callLegRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentLegDialer.originateAgentLeg(anyString(), anyString(), any(), any()))
                .thenReturn(AGENT_UUID);
        when(didRepository.findByIdAndDeletedAtIsNull(DID))
                .thenReturn(java.util.Optional.of(new com.shivang.obd.did.DidEntity() {{
                    setId(DID);
                    setE164Number("+15550001111");
                }}));
    }

    // --- connect flow ----------------------------------------------------

    @Test
    @DisplayName("C1: connect reserves the agent, creates the AGENT leg and originates it")
    void connectCreatesLegAndOriginates() {
        stubHappySelection();

        var result = service.connectByAgent(SESSION, ATTEMPT);

        assertThat(result.isSelected()).isTrue();
        assertThat(result.candidate().getId()).isEqualTo(AGENT);
        ArgumentCaptor<CallLeg> legCaptor = ArgumentCaptor.forClass(CallLeg.class);
        verify(callLegRepository, org.mockito.Mockito.atLeastOnce()).save(legCaptor.capture());
        CallLeg saved = legCaptor.getValue();
        assertThat(saved.getLegType()).isEqualTo(CallLegType.AGENT);
        assertThat(saved.getAgentId()).isEqualTo(AGENT);
        assertThat(saved.getProviderCallId()).isEqualTo(AGENT_UUID);
        assertThat(saved.getTarget()).isEqualTo("sip:agent1@example.test");
        verify(reservationService).reserve(eq(AGENT), eq(TENANT), eq(SESSION), eq(ATTEMPT));
        verify(reservationService).attachLeg(any(), eq(SESSION));
        assertThat(sessionCaptor().getStatus()).isEqualTo(CallSessionStatus.CONNECTING_AGENT);
    }

    private CallSession sessionCaptor() {
        ArgumentCaptor<CallSession> cap = ArgumentCaptor.forClass(CallSession.class);
        verify(callSessionRepository, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("C2: duplicate CONNECT request reuses the existing agent leg (no second reserve/originate)")
    void duplicateConnectIsIdempotent() {
        stubHappySelection();
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(SESSION, CallLegType.AGENT))
                .thenReturn(List.of(agentLeg(CallLegStatus.DIALING)));
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(AGENT, TENANT))
                .thenReturn(Optional.of(eligibleAgent()));

        var result = service.connectByAgent(SESSION, ATTEMPT);

        assertThat(result.isSelected()).isTrue();
        verify(reservationService, never()).reserve(any(), any(), any(), any());
        verify(agentLegDialer, never()).originateAgentLeg(any(), any(), any(), any());
    }

    @Test
    @DisplayName("C3: no eligible agent → session FAILED with reason, no reservation attempted")
    void noEligibleAgentFailsSession() {
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(sessionIn(CallSessionStatus.WAITING_FOR_DTMF)));
        when(agentRepository.findEligibleOrdered(eq(TENANT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(agentRepository.findByTenantIdAndDeletedAtIsNull(eq(TENANT), any(Pageable.class)))
                .thenReturn(List.of());
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.connectByAgent(SESSION, ATTEMPT);

        assertThat(result.isSelected()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(AgentReasons.AGENT_UNAVAILABLE);
        verify(reservationService, never()).reserve(any(), any(), any(), any());
        CallSession saved = sessionCaptor();
        assertThat(saved.getStatus()).isEqualTo(CallSessionStatus.FAILED);
        assertThat(saved.getFailureCode()).isEqualTo(AgentReasons.AGENT_UNAVAILABLE);
    }

    @Test
    @DisplayName("C4: reservation lost/limited → AGENT_BUSY, session failed, no agent leg")
    void reservationFailureYieldsBusy() {
        stubHappySelection();
        when(reservationService.reserve(eq(AGENT), eq(TENANT), eq(SESSION), any()))
                .thenReturn(Optional.empty());

        var result = service.connectByAgent(SESSION, ATTEMPT);

        assertThat(result.isSelected()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(AgentReasons.AGENT_BUSY);
        verify(agentLegDialer, never()).originateAgentLeg(any(), any(), any(), any());
        assertThat(sessionCaptor().getFailureCode()).isEqualTo(AgentReasons.AGENT_BUSY);
    }

    @Test
    @DisplayName("C5: originate failure → leg FAILED, reservation released, session failed")
    void originateFailureReleasesReservation() {
        stubHappySelection();
        when(agentLegDialer.originateAgentLeg(anyString(), anyString(), any(), any()))
                .thenThrow(new com.shivang.obd.telephony.EslException("originate rejected"));

        var result = service.connectByAgent(SESSION, ATTEMPT);

        assertThat(result.isSelected()).isFalse();
        ArgumentCaptor<CallLeg> legCaptor = ArgumentCaptor.forClass(CallLeg.class);
        verify(callLegRepository, org.mockito.Mockito.atLeastOnce()).save(legCaptor.capture());
        // The leg is saved with AGENT uuid then saved again as FAILED — the
        // LAST save must be the failed state.
        var savedLegs = legCaptor.getAllValues();
        CallLeg lastSave = savedLegs.get(savedLegs.size() - 1);
        assertThat(lastSave.getStatus()).isEqualTo(CallLegStatus.FAILED);
        assertThat(lastSave.getFailureCode()).isEqualTo(AgentReasons.AGENT_ORIGINATE_FAILED);
        verify(reservationService).releaseForCallSession(SESSION, ReleaseReasons.ORIGINATE_FAILED);
        assertThat(sessionCaptor().getFailureCode()).isEqualTo("AGENT_ORIGINATE_FAILED");
    }

    @Test
    @DisplayName("C6: terminal session never connects (state guard)")
    void terminalSessionRefused() {
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(sessionIn(CallSessionStatus.COMPLETED)));

        var result = service.connectByAgent(SESSION, ATTEMPT);

        assertThat(result.isSelected()).isFalse();
        verify(reservationService, never()).reserve(any(), any(), any(), any());
    }

    // --- agent leg lifecycle ---------------------------------------------

    @Test
    @DisplayName("C7: agent leg ringing transitions DIALING → RINGING (idempotent)")
    void agentRinging() {
        CallLeg leg = agentLeg(CallLegStatus.DIALING);
        service.onAgentLegRinging(leg);
        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.RINGING);

        // Duplicate progress: no regression.
        service.onAgentLegRinging(leg);
        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.RINGING);
    }

    @Test
    @DisplayName("C8: agent answer requests the bridge via the media boundary")
    void agentAnswerRequestsBridge() {
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(sessionIn(CallSessionStatus.CONNECTING_AGENT)));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(SESSION, CallLegType.CUSTOMER))
                .thenReturn(List.of(callerLeg(CallLegStatus.ANSWERED)));
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CallLeg leg = agentLeg(CallLegStatus.RINGING);
        service.onAgentLegAnswered(leg);

        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.ANSWERED);
        verify(mediaController).bridge(eq(SESSION), any(), eq(leg.getId()));
    }

    @Test
    @DisplayName("C9: duplicate ANSWER never bridges twice")
    void duplicateAnswerNoDoubleBridge() {
        CallLeg leg = agentLeg(CallLegStatus.ANSWERED);
        service.onAgentLegAnswered(leg);
        verify(mediaController, never()).bridge(any(), any(), any());
    }

    @Test
    @DisplayName("C10: bridge command failure fails the leg + session and releases the reservation")
    void bridgeCommandFailure() {
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(sessionIn(CallSessionStatus.CONNECTING_AGENT)));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(SESSION, CallLegType.CUSTOMER))
                .thenReturn(List.of(callerLeg(CallLegStatus.ANSWERED)));
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new com.shivang.obd.telephony.EslException("bridge rejected"))
                .when(mediaController).bridge(any(), any(), any());

        CallLeg leg = agentLeg(CallLegStatus.RINGING);
        service.onAgentLegAnswered(leg);

        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.FAILED);
        assertThat(leg.getFailureCode()).isEqualTo("AGENT_BRIDGE_FAILED");
        verify(reservationService).releaseForCallSession(SESSION, ReleaseReasons.BRIDGE_FAILED);
        assertThat(sessionCaptor().getFailureCode()).isEqualTo("AGENT_BRIDGE_FAILED");
    }

    @Test
    @DisplayName("C11: bridge confirmation establishes BRIDGED on session + legs, marks reservation ACTIVE")
    void bridgeConfirmed() {
        CallSession session = sessionIn(CallSessionStatus.CONNECTING_AGENT);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION)).thenReturn(Optional.of(session));
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(SESSION, CallLegType.CUSTOMER))
                .thenReturn(List.of(callerLeg(CallLegStatus.ANSWERED)));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(SESSION, CallLegType.AGENT))
                .thenReturn(List.of(agentLeg(CallLegStatus.ANSWERED)));

        service.onBridgeConfirmed(SESSION, AGENT_UUID);

        assertThat(session.getStatus()).isEqualTo(CallSessionStatus.BRIDGED);
        verify(reservationService).markActiveForCallSession(SESSION);

        // Duplicate confirmation is a no-op.
        service.onBridgeConfirmed(SESSION, AGENT_UUID);
        verify(reservationService, org.mockito.Mockito.times(1)).markActiveForCallSession(SESSION);
    }

    @Test
    @DisplayName("C12: agent hangup after bridge releases reservation and tears down caller")
    void agentHangupAfterBridge() {
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(sessionIn(CallSessionStatus.BRIDGED)));
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(SESSION, CallLegType.CUSTOMER))
                .thenReturn(List.of(callerLeg(CallLegStatus.BRIDGED)));

        CallLeg leg = agentLeg(CallLegStatus.BRIDGED);
        service.onAgentLegHangup(leg, "NORMAL_CLEARING");

        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.COMPLETED);
        verify(reservationService).releaseForCallSession(SESSION, ReleaseReasons.CALL_ENDED);
        verify(mediaController).terminateCall(SESSION, CALLER_UUID);
    }

    @Test
    @DisplayName("C13: agent hangup before bridge fails the connect cleanly (one attempt, no queue)")
    void agentHangupBeforeBridge() {
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(sessionIn(CallSessionStatus.CONNECTING_AGENT)));
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(SESSION, CallLegType.CUSTOMER))
                .thenReturn(List.of(callerLeg(CallLegStatus.ANSWERED)));

        CallLeg leg = agentLeg(CallLegStatus.RINGING);
        service.onAgentLegHangup(leg, "NORMAL_CLEARING");

        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.FAILED);
        assertThat(sessionCaptor().getFailureCode()).isEqualTo("AGENT_CONNECT_FAILED");
        verify(reservationService).releaseForCallSession(SESSION, ReleaseReasons.CALL_ENDED);
    }

    @Test
    @DisplayName("C14: duplicate agent hangup is a no-op")
    void duplicateAgentHangup() {
        CallLeg leg = agentLeg(CallLegStatus.FAILED);
        service.onAgentLegHangup(leg, "NORMAL_CLEARING");

        verify(reservationService, never()).releaseForCallSession(any(), any());
    }

    @Test
    @DisplayName("C15: caller hangup releases the agent reservation and cancels the agent leg")
    void callerHangupCleanup() {
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(SESSION, CallLegType.AGENT))
                .thenReturn(List.of(agentLeg(CallLegStatus.RINGING)));

        service.onCallerHangup(SESSION);

        verify(reservationService).releaseForCallSession(SESSION, ReleaseReasons.CALLER_HANGUP);
        verify(mediaController).terminateCall(SESSION, AGENT_UUID);
    }

    @Test
    @DisplayName("C16: caller hangup with no agent leg still releases (no reservation → no-op)")
    void callerHangupWithoutAgentLeg() {
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(SESSION, CallLegType.AGENT))
                .thenReturn(List.of());

        service.onCallerHangup(SESSION);

        verify(reservationService).releaseForCallSession(SESSION, ReleaseReasons.CALLER_HANGUP);
        verify(mediaController, never()).terminateCall(any(), any());
    }
}
