package com.shivang.obd.voice.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.voice.media.OutboundDialRequest;
import com.shivang.obd.voice.media.OutboundDialResponse;
import com.shivang.obd.voice.media.OutboundDialResult;
import com.shivang.obd.voice.media.OutboundDialer;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.agent.Agent;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentAvailability;
import com.shivang.obd.voice.agent.AgentConnectEvents;
import com.shivang.obd.voice.agent.AgentEndpointEntity;
import com.shivang.obd.voice.agent.AgentEndpointRepository;
import com.shivang.obd.voice.agent.AgentLegDialer;
import com.shivang.obd.voice.agent.AgentReasons;
import com.shivang.obd.voice.agent.AgentRepository;
import com.shivang.obd.voice.agent.AgentReservation;
import com.shivang.obd.voice.agent.AgentReservationService;
import com.shivang.obd.voice.agent.ReleaseReasons;
import com.shivang.obd.voice.call.CallDirection;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.call.CallType;
import com.shivang.obd.voice.call.EndpointType;
import com.shivang.obd.voice.media.VoiceMediaController;
import com.shivang.obd.voice.routing.VoiceRoute;
import com.shivang.obd.voice.routing.VoiceRoutingDecision;
import com.shivang.obd.voice.routing.VoiceRoutingService;
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

/**
 * VB-4E unit tests: agent eligibility gating, destination validation,
 * routing/capacity/reservation sequencing, canonical call creation,
 * synchronous failure unwind, customer-answer connect, and hangup cleanup.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentOutboundCallServiceTest {

    private static final UUID TENANT = UUID.fromString("eb000000-0000-4000-8000-0000000000e1");
    private static final UUID AGENT = UUID.fromString("eb000000-0000-4000-8000-0000000000a2");
    private static final UUID GATEWAY = UUID.fromString("eb000000-0000-4000-8000-0000000000a3");
    private static final UUID DID = UUID.fromString("eb000000-0000-4000-8000-0000000000a4");
    private static final String DID_E164 = "+911140404040";
    private static final String DEST = "+919810000000";

    @Mock private AgentRepository agentRepository;
    @Mock private AgentEndpointRepository endpointRepository;
    @Mock private AgentReservationService reservationService;
    @Mock private VoiceRoutingService voiceRoutingService;
    @Mock private com.shivang.obd.voice.capacity.VoiceCapacityService voiceCapacity;
    @Mock private OutboundDialer outboundDialer;
    @Mock private AgentLegDialer agentLegDialer;
    @Mock private VoiceMediaController mediaController;
    @Mock private AgentConnectEvents agentConnectEvents;
    @Mock private TenantRepository tenantRepository;
    @Mock private com.shivang.obd.did.DidRepository didRepository;
    @Mock private CallSessionRepository callSessionRepository;
    @Mock private CallLegRepository callLegRepository;

    private AgentOutboundCallService service;

    @BeforeEach
    void setUp() {
        service = new AgentOutboundCallService(
                agentRepository, endpointRepository, reservationService,
                voiceRoutingService, voiceCapacity, outboundDialer, agentLegDialer,
                mediaController, Optional.of(agentConnectEvents), tenantRepository,
                didRepository, callSessionRepository, callLegRepository);

        Agent agent = agent();
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(AGENT, TENANT))
                .thenReturn(Optional.of(agent));
        when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(AGENT, TENANT))
                .thenReturn(List.of(endpoint()));
        when(tenantRepository.findByIdAndDeletedAtIsNull(TENANT))
                .thenReturn(Optional.of(new com.shivang.obd.tenant.TenantEntity()));
        // CLI DID resolution (VB-4E): tenant has an assigned, active DID.
        com.shivang.obd.did.DidEntity cliDid = new com.shivang.obd.did.DidEntity();
        cliDid.setId(DID);
        when(didRepository.findFirstByTenantIdAndDeletedAtIsNullAndStatusAndAllocationStateOrderByIdAsc(
                eq(TENANT), any(), any())).thenReturn(Optional.of(cliDid));
        VoiceRoute route = new VoiceRoute(GATEWAY, "gw-out", "external", "twilio", DID, DID_E164);
        when(voiceRoutingService.resolveRoute(eq(TENANT), any(), eq(DEST), any(), anyString(), any()))
                .thenReturn(VoiceRoutingDecision.primary(route, "ROUTE_SELECTED_PRIMARY", List.of()));
        when(voiceCapacity.reserve(GATEWAY, TENANT)).thenReturn(true);
        when(reservationService.reserve(eq(AGENT), eq(TENANT), any(), any()))
                .thenReturn(Optional.of(new AgentReservation()));
        when(outboundDialer.dial(any(OutboundDialRequest.class)))
                .thenReturn(OutboundDialResponse.accepted("fs-customer-uuid"));
        // Save stubs return the argument with an id (real flows are JPA-managed).
        when(callSessionRepository.save(any(CallSession.class))).thenAnswer(inv -> {
            CallSession s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            return s;
        });
        when(callLegRepository.save(any(CallLeg.class))).thenAnswer(inv -> {
            CallLeg l = inv.getArgument(0);
            if (l.getId() == null) {
                l.setId(UUID.randomUUID());
            }
            return l;
        });
    }

    private Agent agent() {
        Agent a = new Agent();
        a.setId(AGENT);
        a.setTenantId(TENANT);
        a.setAdminStatus(AgentAdminStatus.ACTIVE);
        a.setAvailability(AgentAvailability.AVAILABLE);
        a.setMaxConcurrentCalls(1);
        return a;
    }

    private AgentEndpointEntity endpoint() {
        AgentEndpointEntity e = new AgentEndpointEntity();
        e.setAgentId(AGENT);
        e.setTenantId(TENANT);
        e.setEndpointType(EndpointType.SIP);
        e.setDialTarget("sip:agent@pbx.example.com");
        e.setEnabled(true);
        return e;
    }

    private CallLeg savedSession() {
        CallSession s = new CallSession();
        s.setId(UUID.randomUUID());
        s.setTenantId(TENANT);
        s.setCallType(CallType.CONTACT_CENTER_OUTBOUND);
        s.setGatewayId(GATEWAY);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(s.getId()))
                .thenReturn(Optional.of(s));
        CallLeg customer = new CallLeg();
        customer.setId(UUID.randomUUID());
        customer.setCallSessionId(s.getId());
        customer.setLegType(CallLegType.CUSTOMER);
        customer.setProviderCallId("fs-customer-uuid");
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(s.getId(), CallLegType.CUSTOMER))
                .thenReturn(List.of(customer));
        return customer;
    }

    // === agent eligibility ===

    @Test
    @DisplayName("O1: eligible agent → accepted, canonical outbound session with AGENT + CUSTOMER legs")
    void eligibleAgentAccepted() {
        AgentOutboundCallResult result = service.placeCall(TENANT, AGENT, DEST);

        assertThat(result.destinationNumber()).isEqualTo(DEST);
        ArgumentCaptor<CallSession> sessionCap = ArgumentCaptor.forClass(CallSession.class);
        verify(callSessionRepository, atLeastOnce()).save(sessionCap.capture());
        CallSession firstSave = sessionCap.getAllValues().get(0);
        assertThat(firstSave.getCallType()).isEqualTo(CallType.CONTACT_CENTER_OUTBOUND);
        assertThat(firstSave.getDirection()).isEqualTo(CallDirection.OUTBOUND);
        assertThat(firstSave.getStatus()).isEqualTo(CallSessionStatus.DIALING);
        assertThat(firstSave.getGatewayId()).isEqualTo(GATEWAY);
        assertThat(firstSave.getDestinationNumber()).isEqualTo(DEST);

        ArgumentCaptor<CallLeg> legCap = ArgumentCaptor.forClass(CallLeg.class);
        verify(callLegRepository, atLeastOnce()).save(legCap.capture());
        List<CallLeg> legs = legCap.getAllValues();
        assertThat(legs).extracting(CallLeg::getLegType)
                .contains(CallLegType.AGENT, CallLegType.CUSTOMER);
        assertThat(legs).extracting(CallLeg::getStatus)
                .contains(CallLegStatus.INITIATED, CallLegStatus.DIALING);

        verify(voiceCapacity).reserve(GATEWAY, TENANT);
        verify(reservationService).reserve(eq(AGENT), eq(TENANT), any(), any());
        verify(outboundDialer).dial(any(OutboundDialRequest.class));
    }

    @Test
    @DisplayName("O2: missing agent in tenant → fail closed (404-equivalent)")
    void missingAgentRejected() {
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(AGENT, TENANT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, DEST))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(AgentReasons.AGENT_CONFIG_INVALID);
        verify(outboundDialer, never()).dial(any());
    }

    @Test
    @DisplayName("O3: disabled agent rejected")
    void disabledAgentRejected() {
        Agent agent = agent();
        agent.setAdminStatus(AgentAdminStatus.DISABLED);
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(AGENT, TENANT))
                .thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, DEST))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(com.shivang.obd.voice.agent.AgentFoundationReasons.AGENT_DISABLED);
        verify(voiceRoutingService, never()).resolveRoute(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("O4: suspended agent rejected")
    void suspendedAgentRejected() {
        Agent agent = agent();
        agent.setAdminStatus(AgentAdminStatus.SUSPENDED);
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(AGENT, TENANT))
                .thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, DEST))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(com.shivang.obd.voice.agent.AgentFoundationReasons.AGENT_SUSPENDED);
    }

    @Test
    @DisplayName("O5: offline agent rejected")
    void offlineAgentRejected() {
        Agent agent = agent();
        agent.setAvailability(AgentAvailability.OFFLINE);
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(AGENT, TENANT))
                .thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, DEST))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(com.shivang.obd.voice.agent.AgentFoundationReasons.AGENT_OFFLINE);
        verify(voiceRoutingService, never()).resolveRoute(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("O6: busy agent rejected (BUSY is owned by the reservation lifecycle)")
    void busyAgentRejected() {
        Agent agent = agent();
        agent.setAvailability(AgentAvailability.BUSY);
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(AGENT, TENANT))
                .thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, DEST))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(AgentReasons.AGENT_BUSY);
    }

    @Test
    @DisplayName("O7: no dialable endpoint rejected — no routing, no capacity hold")
    void noEndpointRejected() {
        when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(AGENT, TENANT))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, DEST))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(AgentReasons.AGENT_ENDPOINT_INVALID);
        verify(voiceCapacity, never()).reserve(any(), any());
        verify(reservationService, never()).reserve(any(), any(), any(), any());
    }

    // === destination ===

    @Test
    @DisplayName("O8: blank destination rejected")
    void blankDestinationRejected() {
        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, "  "))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(AgentOutboundReasons.INVALID_DESTINATION);
    }

    @Test
    @DisplayName("O9: structurally invalid destination rejected")
    void invalidDestinationRejected() {
        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, "12345"))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(AgentOutboundReasons.INVALID_DESTINATION);
        verify(voiceRoutingService, never()).resolveRoute(any(), any(), any(), any(), any(), any());
    }

    // === routing / capacity / reservation ===

    @Test
    @DisplayName("O10: no eligible gateway → rejected, no session, no capacity hold")
    void noGatewayRejected() {
        when(voiceRoutingService.resolveRoute(eq(TENANT), any(), eq(DEST), any(), anyString(), any()))
                .thenReturn(VoiceRoutingDecision.rejected("ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY", List.of()));

        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, DEST))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(AgentOutboundReasons.NO_ELIGIBLE_GATEWAY);
        verify(callSessionRepository, never()).save(any());
        verify(voiceCapacity, never()).reserve(any(), any());
    }

    @Test
    @DisplayName("O11: gateway capacity exhausted → rejected, no agent reservation, no originate")
    void capacityExhaustedRejected() {
        when(voiceCapacity.reserve(GATEWAY, TENANT)).thenReturn(false);

        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, DEST))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(AgentOutboundReasons.GATEWAY_CAPACITY_EXHAUSTED);
        verify(reservationService, never()).reserve(any(), any(), any(), any());
        verify(outboundDialer, never()).dial(any());
        verify(callSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("O12: agent reservation lost → gateway capacity released, no originate")
    void reservationLostUnwindsCapacity() {
        when(reservationService.reserve(eq(AGENT), eq(TENANT), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, DEST))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(AgentReasons.AGENT_BUSY);
        verify(voiceCapacity).release(GATEWAY, TENANT);
        verify(outboundDialer, never()).dial(any());
    }

    @Test
    @DisplayName("O13: originate failure → legs/session failed, capacity + reservation released")
    void originateFailureUnwindsEverything() {
        when(outboundDialer.dial(any(OutboundDialRequest.class)))
                .thenReturn(OutboundDialResponse.failed("gateway down"));

        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, DEST))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(AgentOutboundReasons.CALL_ORIGINATE_FAILED);

        ArgumentCaptor<CallLeg> legCap = ArgumentCaptor.forClass(CallLeg.class);
        verify(callLegRepository, atLeastOnce()).save(legCap.capture());
        assertThat(legCap.getAllValues()).extracting(CallLeg::getStatus)
                .contains(CallLegStatus.FAILED);
        verify(voiceCapacity).release(GATEWAY, TENANT);
        verify(reservationService).releaseForCallSession(any(), eq(ReleaseReasons.ORIGINATE_FAILED));
    }

    @Test
    @DisplayName("O14: ESL exception during originate → same deterministic unwind")
    void eslExceptionUnwinds() {
        when(outboundDialer.dial(any(OutboundDialRequest.class)))
                .thenThrow(new com.shivang.obd.voice.media.OutboundDialException("ESL down"));

        assertThatThrownBy(() -> service.placeCall(TENANT, AGENT, DEST))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(AgentOutboundReasons.CALL_ORIGINATE_FAILED);
        verify(voiceCapacity).release(GATEWAY, TENANT);
        verify(reservationService).releaseForCallSession(any(), eq(ReleaseReasons.ORIGINATE_FAILED));
    }

    // === lifecycle: customer answer → agent originate ===

    @Test
    @DisplayName("O15: customer answered → agent leg originated, session CONNECTING_AGENT")
    void customerAnswerOriginatesAgent() {
        CallLeg customer = savedSession();
        customer.setStatus(CallLegStatus.RINGING);
        CallLeg agentLeg = new CallLeg();
        agentLeg.setId(UUID.randomUUID());
        agentLeg.setAgentId(AGENT);
        agentLeg.setStatus(CallLegStatus.INITIATED);
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                customer.getCallSessionId(), CallLegType.AGENT)).thenReturn(List.of(agentLeg));
        when(agentLegDialer.originateAgentLeg(any(), any(), any(), any()))
                .thenReturn("fs-agent-uuid");

        service.onCustomerLegAnswered(customer);

        assertThat(customer.getStatus()).isEqualTo(CallLegStatus.ANSWERED);
        assertThat(agentLeg.getStatus()).isEqualTo(CallLegStatus.DIALING);
        assertThat(agentLeg.getProviderCallId()).isEqualTo("fs-agent-uuid");
        verify(agentLegDialer).originateAgentLeg(any(), eq("sip:agent@pbx.example.com"), any(), any());
    }

    @Test
    @DisplayName("O16: agent leg already originated → duplicate answer is a no-op")
    void duplicateAnswerNoOp() {
        CallLeg customer = savedSession();
        customer.setStatus(CallLegStatus.ANSWERED);

        service.onCustomerLegAnswered(customer);

        verify(agentLegDialer, never()).originateAgentLeg(any(), any(), any(), any());
    }

    @Test
    @DisplayName("O17: no dialable endpoint at answer time → session failed, customer torn down, hold released")
    void answerWithNoEndpointFails() {
        CallLeg customer = savedSession();
        customer.setStatus(CallLegStatus.RINGING);
        CallLeg agentLeg = new CallLeg();
        agentLeg.setId(UUID.randomUUID());
        agentLeg.setAgentId(AGENT);
        agentLeg.setStatus(CallLegStatus.INITIATED);
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                customer.getCallSessionId(), CallLegType.AGENT)).thenReturn(List.of(agentLeg));
        when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(AGENT, TENANT))
                .thenReturn(List.of());

        service.onCustomerLegAnswered(customer);

        assertThat(agentLeg.getStatus()).isEqualTo(CallLegStatus.INITIATED); // untouched
        verify(reservationService).releaseForCallSession(any(), eq(AgentReasons.AGENT_ENDPOINT_INVALID));
        verify(mediaController).terminateCall(any(), anyString());
    }

    @Test
    @DisplayName("O18: agent originate failure at answer time → reservation released, session failed, customer killed")
    void answerOriginateFailureCleansUp() {
        CallLeg customer = savedSession();
        customer.setStatus(CallLegStatus.RINGING);
        CallLeg agentLeg = new CallLeg();
        agentLeg.setId(UUID.randomUUID());
        agentLeg.setAgentId(AGENT);
        agentLeg.setStatus(CallLegStatus.INITIATED);
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                customer.getCallSessionId(), CallLegType.AGENT)).thenReturn(List.of(agentLeg));
        when(agentLegDialer.originateAgentLeg(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("chicken handshake failed"));

        service.onCustomerLegAnswered(customer);

        assertThat(agentLeg.getStatus()).isEqualTo(CallLegStatus.FAILED);
        verify(reservationService).releaseForCallSession(any(), eq(ReleaseReasons.ORIGINATE_FAILED));
        verify(mediaController).terminateCall(any(), anyString());
    }

    // === hangup / cleanup ===

    @Test
    @DisplayName("O19: customer hangup after bridge → COMPLETED, shared cleanup + capacity release run once")
    void customerHangupAfterBridge() {
        CallLeg customer = savedSession();
        customer.setStatus(CallLegStatus.BRIDGED);
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(customer.getCallSessionId()).get();
        session.setStatus(CallSessionStatus.BRIDGED);

        service.onCustomerLegHangup(customer, "NORMAL_CLEARING", TENANT);

        assertThat(session.getStatus()).isEqualTo(CallSessionStatus.COMPLETED);
        assertThat(customer.getStatus()).isEqualTo(CallLegStatus.COMPLETED);
        verify(agentConnectEvents).onCallerHangup(session.getId());
        verify(voiceCapacity).release(GATEWAY, TENANT);
    }

    @Test
    @DisplayName("O20: customer hangup pre-bridge → FAILED with mapped cause; duplicate hangup is a no-op")
    void customerHangupPreBridgeAndDuplicate() {
        CallLeg customer = savedSession();
        customer.setStatus(CallLegStatus.DIALING);
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(customer.getCallSessionId()).get();
        session.setStatus(CallSessionStatus.DIALING);

        service.onCustomerLegHangup(customer, "USER_BUSY", TENANT);
        assertThat(session.getStatus()).isEqualTo(CallSessionStatus.FAILED);
        assertThat(session.getFailureCode()).isEqualTo("BUSY");

        // Duplicate hangup — session already final: no second cleanup.
        org.mockito.Mockito.clearInvocations(agentConnectEvents, voiceCapacity);
        service.onCustomerLegHangup(customer, "USER_BUSY", TENANT);
        verify(agentConnectEvents, never()).onCallerHangup(any());
        verify(voiceCapacity, never()).release(any(), any());
    }
}
