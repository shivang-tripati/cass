package com.shivang.obd.voice.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidInboundDestination;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.voice.acd.AcdResult;
import com.shivang.obd.voice.acd.AcdService;
import com.shivang.obd.voice.agent.Agent;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentAvailability;
import com.shivang.obd.voice.agent.AgentConnectEvents;
import com.shivang.obd.voice.agent.AgentEndpointEntity;
import com.shivang.obd.voice.agent.AgentEndpointRepository;
import com.shivang.obd.voice.agent.AgentRepository;
import com.shivang.obd.voice.agent.AgentReservation;
import com.shivang.obd.voice.agent.AgentReservationService;
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
import com.shivang.obd.voice.queue.Queue;
import com.shivang.obd.voice.queue.QueueRepository;
import com.shivang.obd.voice.queue.QueueWaitingCall;
import com.shivang.obd.voice.queue.QueueWaitingCallRepository;
import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the VB-4D inbound call lifecycle (mocked repositories).
 * Covers: routing (queue/direct/fail-closed), idempotency, canonical
 * domain usage, ACD connect step, and cleanup paths.
 */
class InboundCallServiceTest {

    private static final String CHANNEL = "inbound-channel-uuid";
    private static final String DID_E164 = "+911234567890";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DID_ID = UUID.randomUUID();
    private static final UUID QUEUE_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    private DidRepository didRepository;
    private QueueRepository queueRepository;
    private QueueWaitingCallRepository waitingCallRepository;
    private AgentRepository agentRepository;
    private AgentEndpointRepository endpointRepository;
    private AgentReservationService reservationService;
    private AcdService acdService;
    private CallSessionRepository callSessionRepository;
    private CallLegRepository callLegRepository;
    private com.shivang.obd.voice.agent.AgentLegDialer agentLegDialer;
    private com.shivang.obd.voice.media.VoiceMediaController mediaController;
    private AgentConnectEvents agentConnectEvents;
    private InboundCallService service;

    @BeforeEach
    void setUp() {
        didRepository = mock(DidRepository.class);
        queueRepository = mock(QueueRepository.class);
        waitingCallRepository = mock(QueueWaitingCallRepository.class);
        agentRepository = mock(AgentRepository.class);
        endpointRepository = mock(AgentEndpointRepository.class);
        reservationService = mock(AgentReservationService.class);
        acdService = mock(AcdService.class);
        callSessionRepository = mock(CallSessionRepository.class);
        callLegRepository = mock(CallLegRepository.class);
        agentLegDialer = mock(com.shivang.obd.voice.agent.AgentLegDialer.class);
        mediaController = mock(com.shivang.obd.voice.media.VoiceMediaController.class);
        agentConnectEvents = mock(AgentConnectEvents.class);
        jakarta.persistence.EntityManager entityManager =
                mock(jakarta.persistence.EntityManager.class);
        // Advisory lock stub: fluent chain returns a query whose
        // getSingleResult() yields a row (lock acquired).
        jakarta.persistence.Query lockQuery = mock(jakarta.persistence.Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(anyString(), any())).thenReturn(lockQuery);
        service = new InboundCallService(entityManager,
                didRepository, queueRepository,
                waitingCallRepository, agentRepository, endpointRepository,
                reservationService, acdService, callSessionRepository,
                callLegRepository, agentLegDialer, mediaController,
                Optional.of(agentConnectEvents));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private DidEntity did(DidInboundDestination destination, UUID queueId, UUID agentId) {
        DidEntity did = new DidEntity();
        did.setId(DID_ID);
        did.setTenantId(TENANT);
        did.setE164Number(DID_E164);
        did.setInboundDestination(destination);
        did.setInboundQueueId(queueId);
        did.setInboundAgentId(agentId);
        return did;
    }

    private CallSession session(CallSessionStatus status) {
        CallSession session = new CallSession();
        session.setId(SESSION_ID);
        session.setTenantId(TENANT);
        session.setDirection(CallDirection.INBOUND);
        session.setCallType(CallType.CONTACT_CENTER_INBOUND);
        session.setStatus(status);
        session.setProviderCallId(CHANNEL);
        session.setDestinationNumber(DID_E164);
        return session;
    }

    private CallLeg callerLeg(CallLegStatus status) {
        CallLeg leg = new CallLeg();
        leg.setId(UUID.randomUUID());
        leg.setCallSessionId(SESSION_ID);
        leg.setLegType(CallLegType.CUSTOMER);
        leg.setStatus(status);
        leg.setProviderCallId(CHANNEL);
        return leg;
    }

    private void stubNewSessionCreation() {
        AtomicReference<CallSession> saved = new AtomicReference<>();
        when(callSessionRepository.save(any(CallSession.class))).thenAnswer(inv -> {
            CallSession s = inv.getArgument(0);
            s.setId(SESSION_ID);
            saved.set(s);
            return s;
        });
        when(callLegRepository.save(any(CallLeg.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubAgent(UUID agentId) {
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setTenantId(TENANT);
        agent.setAdminStatus(AgentAdminStatus.ACTIVE);
        agent.setAvailability(AgentAvailability.AVAILABLE);
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agentId, TENANT))
                .thenReturn(Optional.of(agent));
        AgentEndpointEntity endpoint = new AgentEndpointEntity();
        endpoint.setAgentId(agentId);
        endpoint.setTenantId(TENANT);
        endpoint.setEndpointType(EndpointType.SIP);
        endpoint.setDialTarget("sip:agent@" + agentId);
        endpoint.setEnabled(true);
        when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(agentId, TENANT))
                .thenReturn(List.of(endpoint));
    }

    // ------------------------------------------------------------------
    // routing / CHANNEL_CREATE
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R1: CHANNEL_CREATE with QUEUE destination creates canonical session + CUSTOMER leg + WAITING call")
    void queueRoutingCreatesCanonicalState() {
        stubNewSessionCreation();
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CHANNEL))
                .thenReturn(Optional.empty());
        when(didRepository.findByE164NumberAndDeletedAtIsNull(DID_E164))
                .thenReturn(Optional.of(did(DidInboundDestination.QUEUE, QUEUE_ID, null)));
        Queue queue = new Queue();
        queue.setId(QUEUE_ID);
        queue.setTenantId(TENANT);
        queue.setMaxWaitSeconds(120);
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(QUEUE_ID, TENANT))
                .thenReturn(Optional.of(queue));

        Optional<UUID> result = service.onInboundChannelCreated(CHANNEL, DID_E164, "+919876543210");

        assertThat(result).contains(SESSION_ID);
        verify(callSessionRepository).save(any(CallSession.class));
        AtomicReference<CallLeg> leg = new AtomicReference<>();
        verify(callLegRepository).save(org.mockito.ArgumentMatchers.argThat(l -> {
            leg.set(l);
            return true;
        }));
        assertThat(leg.get().getLegType()).isEqualTo(CallLegType.CUSTOMER);
        assertThat(leg.get().getDirection()).isEqualTo(CallDirection.INBOUND);
        assertThat(leg.get().getProviderCallId()).isEqualTo(CHANNEL);
        assertThat(leg.get().getQueueId()).isEqualTo(QUEUE_ID);
        AtomicReference<QueueWaitingCall> wc = new AtomicReference<>();
        verify(waitingCallRepository).save(org.mockito.ArgumentMatchers.argThat(w -> {
            wc.set(w);
            return true;
        }));
        assertThat(wc.get().getStatus()).isEqualTo(QueueWaitingCallStatus.WAITING);
        assertThat(wc.get().getQueueId()).isEqualTo(QUEUE_ID);
        assertThat(wc.get().getExpiresAt()).isNotNull(); // wait-budget snapshot
    }

    @Test
    @DisplayName("R2: duplicate CHANNEL_CREATE (same provider UUID) returns the existing session — no second session")
    void duplicateChannelCreateIsIdempotent() {
        CallSession existing = session(CallSessionStatus.ANSWERED);
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CHANNEL))
                .thenReturn(Optional.of(existing));

        Optional<UUID> result = service.onInboundChannelCreated(CHANNEL, DID_E164, null);

        assertThat(result).contains(SESSION_ID);
        verify(callSessionRepository, never()).save(any(CallSession.class));
        verify(waitingCallRepository, never()).save(any(QueueWaitingCall.class));
    }

    @Test
    @DisplayName("R3: unknown destination DID → ignored, nothing created (fail closed)")
    void unknownDidIgnored() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CHANNEL))
                .thenReturn(Optional.empty());
        when(didRepository.findByE164NumberAndDeletedAtIsNull(DID_E164))
                .thenReturn(Optional.empty());

        assertThat(service.onInboundChannelCreated(CHANNEL, DID_E164, null)).isEmpty();
        verify(callSessionRepository, never()).save(any(CallSession.class));
    }

    @Test
    @DisplayName("R4: DID without inbound destination (outbound-only) → ignored")
    void didWithoutInboundDestinationIgnored() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CHANNEL))
                .thenReturn(Optional.empty());
        DidEntity outboundOnly = did(null, null, null);
        when(didRepository.findByE164NumberAndDeletedAtIsNull(DID_E164))
                .thenReturn(Optional.of(outboundOnly));

        assertThat(service.onInboundChannelCreated(CHANNEL, DID_E164, null)).isEmpty();
        verify(callSessionRepository, never()).save(any(CallSession.class));
    }

    @Test
    @DisplayName("R5: pool DID (tenant NULL) → ignored — inbound routing is tenant-scoped")
    void poolDidIgnored() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CHANNEL))
                .thenReturn(Optional.empty());
        DidEntity poolDid = did(DidInboundDestination.QUEUE, QUEUE_ID, null);
        poolDid.setTenantId(null);
        when(didRepository.findByE164NumberAndDeletedAtIsNull(DID_E164))
                .thenReturn(Optional.of(poolDid));

        assertThat(service.onInboundChannelCreated(CHANNEL, DID_E164, null)).isEmpty();
        verify(callSessionRepository, never()).save(any(CallSession.class));
    }

    @Test
    @DisplayName("R6: QUEUE destination pointing at a missing queue → session failed, no waiting call")
    void missingQueueFailsCall() {
        stubNewSessionCreation();
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CHANNEL))
                .thenReturn(Optional.empty());
        when(didRepository.findByE164NumberAndDeletedAtIsNull(DID_E164))
                .thenReturn(Optional.of(did(DidInboundDestination.QUEUE, QUEUE_ID, null)));
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(QUEUE_ID, TENANT))
                .thenReturn(Optional.empty());

        assertThat(service.onInboundChannelCreated(CHANNEL, DID_E164, null))
                .contains(SESSION_ID);
        verify(waitingCallRepository, never()).save(any(QueueWaitingCall.class));
        verify(callSessionRepository, org.mockito.Mockito.atLeastOnce())
                .save(org.mockito.ArgumentMatchers.argThat(s ->
                        s.getStatus() == CallSessionStatus.FAILED
                                && "INBOUND_ROUTE_INVALID".equals(s.getFailureCode())));
    }

    @Test
    @DisplayName("R7: direct-AGENT destination reserves and originates the agent leg (canonical AGENT leg)")
    void directAgentRouting() {
        stubNewSessionCreation();
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CHANNEL))
                .thenReturn(Optional.empty());
        when(didRepository.findByE164NumberAndDeletedAtIsNull(DID_E164))
                .thenReturn(Optional.of(did(DidInboundDestination.AGENT, null, AGENT_ID)));
        stubAgent(AGENT_ID);
        AgentReservation reservation = new AgentReservation();
        reservation.setId(UUID.randomUUID());
        when(reservationService.reserve(AGENT_ID, TENANT, SESSION_ID, null))
                .thenReturn(Optional.of(reservation));
        when(agentLegDialer.originateAgentLeg(any(), any(), any(), any()))
                .thenReturn("agent-uuid-1");

        Optional<UUID> result = service.onInboundChannelCreated(CHANNEL, DID_E164, null);

        assertThat(result).contains(SESSION_ID);
        verify(reservationService).reserve(AGENT_ID, TENANT, SESSION_ID, null);
        verify(reservationService).attachLeg(any(), eq(SESSION_ID));
        verify(callLegRepository, org.mockito.Mockito.atLeastOnce())
                .save(org.mockito.ArgumentMatchers.argThat(l ->
                        l.getLegType() == CallLegType.AGENT
                                && l.getAgentId().equals(AGENT_ID)
                                && "agent-uuid-1".equals(l.getProviderCallId())));
        verify(callSessionRepository, org.mockito.Mockito.atLeastOnce())
                .save(org.mockito.ArgumentMatchers.argThat(s ->
                        s.getStatus() == CallSessionStatus.CONNECTING_AGENT));
    }

    @Test
    @DisplayName("R8: direct-agent originate failure releases the reservation and fails the session — no orphan hold")
    void directAgentOriginateFailureReleases() {
        stubNewSessionCreation();
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CHANNEL))
                .thenReturn(Optional.empty());
        when(didRepository.findByE164NumberAndDeletedAtIsNull(DID_E164))
                .thenReturn(Optional.of(did(DidInboundDestination.AGENT, null, AGENT_ID)));
        stubAgent(AGENT_ID);
        when(reservationService.reserve(AGENT_ID, TENANT, SESSION_ID, null))
                .thenReturn(Optional.of(new AgentReservation()));
        when(agentLegDialer.originateAgentLeg(any(), any(), any(), any()))
                .thenThrow(new com.shivang.obd.telephony.EslException("down"));

        service.onInboundChannelCreated(CHANNEL, DID_E164, null);

        verify(reservationService).releaseForCallSession(SESSION_ID,
                com.shivang.obd.voice.agent.ReleaseReasons.ORIGINATE_FAILED);
        verify(callSessionRepository, org.mockito.Mockito.atLeastOnce())
                .save(org.mockito.ArgumentMatchers.argThat(s ->
                        s.getStatus() == CallSessionStatus.FAILED
                                && "AGENT_ORIGINATE_FAILED".equals(s.getFailureCode())));
    }

    @Test
    @DisplayName("R9: direct-agent at capacity (reservation refused) → session FAILED AGENT_BUSY, no leg dialed")
    void directAgentBusyFailsCall() {
        stubNewSessionCreation();
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CHANNEL))
                .thenReturn(Optional.empty());
        when(didRepository.findByE164NumberAndDeletedAtIsNull(DID_E164))
                .thenReturn(Optional.of(did(DidInboundDestination.AGENT, null, AGENT_ID)));
        stubAgent(AGENT_ID);
        when(reservationService.reserve(AGENT_ID, TENANT, SESSION_ID, null))
                .thenReturn(Optional.empty());

        service.onInboundChannelCreated(CHANNEL, DID_E164, null);

        verify(agentLegDialer, never()).originateAgentLeg(any(), any(), any(), any());
        verify(callSessionRepository, org.mockito.Mockito.atLeastOnce())
                .save(org.mockito.ArgumentMatchers.argThat(s ->
                        s.getStatus() == CallSessionStatus.FAILED));
    }

    // ------------------------------------------------------------------
    // caller events
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C1: caller ANSWER moves INITIATED session to ANSWERED and marks the leg")
    void callerAnswered() {
        CallLeg leg = callerLeg(CallLegStatus.INITIATED);
        CallSession s = session(CallSessionStatus.INITIATED);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(s));

        service.onInboundCallerAnswered(leg);

        assertThat(s.getStatus()).isEqualTo(CallSessionStatus.ANSWERED);
        assertThat(s.getAnsweredAt()).isNotNull();
        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.ANSWERED);
    }

    @Test
    @DisplayName("C2: duplicate caller ANSWER never regresses state")
    void duplicateAnswerNoOp() {
        CallLeg leg = callerLeg(CallLegStatus.ANSWERED);
        CallSession s = session(CallSessionStatus.CONNECTING_AGENT);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(s));

        service.onInboundCallerAnswered(leg);

        assertThat(s.getStatus()).isEqualTo(CallSessionStatus.CONNECTING_AGENT);
    }

    @Test
    @DisplayName("C3: caller hangup while WAITING → queue entry REMOVED, reservation released, agent leg torn down, session FAILED")
    void callerHangupWhileWaiting() {
        CallLeg leg = callerLeg(CallLegStatus.ANSWERED);
        CallSession s = session(CallSessionStatus.CONNECTING_AGENT);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(s));
        when(waitingCallRepository.markWaitingRemovedForSession(SESSION_ID)).thenReturn(1);

        service.onInboundCallerHangup(leg, "NORMAL_CLEARING");

        verify(waitingCallRepository).markWaitingRemovedForSession(SESSION_ID);
        verify(agentConnectEvents).onCallerHangup(SESSION_ID);
        assertThat(s.getStatus()).isEqualTo(CallSessionStatus.FAILED);
        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.FAILED);
    }

    @Test
    @DisplayName("C4: caller hangup after bridge with NORMAL_CLEARING → COMPLETED")
    void callerHangupAfterBridgeCompletes() {
        CallLeg leg = callerLeg(CallLegStatus.BRIDGED);
        CallSession s = session(CallSessionStatus.BRIDGED);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(s));

        service.onInboundCallerHangup(leg, "NORMAL_CLEARING");

        assertThat(s.getStatus()).isEqualTo(CallSessionStatus.COMPLETED);
        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.COMPLETED);
    }

    @Test
    @DisplayName("C5: duplicate caller hangup is idempotent — session not re-finalized")
    void duplicateHangupNoOp() {
        CallLeg leg = callerLeg(CallLegStatus.COMPLETED);
        CallSession s = session(CallSessionStatus.FAILED);
        s.setFailureCode("CALLER_HANGUP");
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(s));

        service.onInboundCallerHangup(leg, "NORMAL_CLEARING");

        verify(agentConnectEvents, never()).onCallerHangup(any());
        assertThat(s.getStatus()).isEqualTo(CallSessionStatus.FAILED);
    }

    @Test
    @DisplayName("C6: caller hangup during agent ringing tears down the agent leg via the shared boundary")
    void callerHangupDuringRinging() {
        CallLeg leg = callerLeg(CallLegStatus.ANSWERED);
        CallSession s = session(CallSessionStatus.CONNECTING_AGENT);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(s));

        service.onInboundCallerHangup(leg, "NORMAL_CLEARING");

        // Shared VB-3 boundary: releases reservation + terminates agent leg.
        verify(agentConnectEvents).onCallerHangup(SESSION_ID);
        assertThat(s.getStatus()).isEqualTo(CallSessionStatus.FAILED);
    }

    // ------------------------------------------------------------------
    // ACD connect step
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A1: connectAssignedAgent creates the AGENT leg and originates via the dialer")
    void connectAssignedAgentOriginates() {
        QueueWaitingCall wc = new QueueWaitingCall();
        wc.setId(UUID.randomUUID());
        wc.setTenantId(TENANT);
        wc.setQueueId(QUEUE_ID);
        wc.setCallSessionId(SESSION_ID);
        wc.setStatus(QueueWaitingCallStatus.ASSIGNED);
        wc.setAssignedAgentId(AGENT_ID);
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(wc.getId()))
                .thenReturn(Optional.of(wc));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                SESSION_ID, CallLegType.AGENT)).thenReturn(List.of());
        CallSession s = session(CallSessionStatus.ANSWERED);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(s));
        stubAgent(AGENT_ID);
        when(callLegRepository.save(any(CallLeg.class))).thenAnswer(inv -> inv.getArgument(0));
        when(agentLegDialer.originateAgentLeg(any(), any(), any(), any()))
                .thenReturn("agent-uuid-9");

        boolean connected = service.connectAssignedAgent(wc.getId());

        assertThat(connected).isTrue();
        verify(agentLegDialer).originateAgentLeg(eq(DID_E164), anyString(), any(), any());
        verify(reservationService).attachLeg(any(), eq(SESSION_ID));
        assertThat(s.getStatus()).isEqualTo(CallSessionStatus.CONNECTING_AGENT);
    }

    @Test
    @DisplayName("A2: connectAssignedAgent is idempotent — an existing agent leg short-circuits")
    void connectAssignedAgentIdempotent() {
        QueueWaitingCall wc = new QueueWaitingCall();
        wc.setId(UUID.randomUUID());
        wc.setCallSessionId(SESSION_ID);
        wc.setStatus(QueueWaitingCallStatus.ASSIGNED);
        wc.setAssignedAgentId(AGENT_ID);
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(wc.getId()))
                .thenReturn(Optional.of(wc));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                SESSION_ID, CallLegType.AGENT))
                .thenReturn(List.of(callerLeg(CallLegStatus.DIALING)));

        assertThat(service.connectAssignedAgent(wc.getId())).isTrue();
        verify(agentLegDialer, never()).originateAgentLeg(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A3: connectAssignedAgent on a non-ASSIGNED (released/timed-out) call returns false")
    void connectNonAssignedReturnsFalse() {
        QueueWaitingCall wc = new QueueWaitingCall();
        wc.setId(UUID.randomUUID());
        wc.setStatus(QueueWaitingCallStatus.WAITING);
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(wc.getId()))
                .thenReturn(Optional.of(wc));

        assertThat(service.connectAssignedAgent(wc.getId())).isFalse();
        verify(agentLegDialer, never()).originateAgentLeg(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A4: assigned agent lost its endpoint → assignment released, call returns to queue flow")
    void connectWithDeadEndpointReleasesAssignment() {
        QueueWaitingCall wc = new QueueWaitingCall();
        wc.setId(UUID.randomUUID());
        wc.setCallSessionId(SESSION_ID);
        wc.setStatus(QueueWaitingCallStatus.ASSIGNED);
        wc.setAssignedAgentId(AGENT_ID);
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(wc.getId()))
                .thenReturn(Optional.of(wc));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                SESSION_ID, CallLegType.AGENT)).thenReturn(List.of());
        CallSession s = session(CallSessionStatus.ANSWERED);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(s));
        when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(AGENT_ID, TENANT))
                .thenReturn(List.of());

        assertThat(service.connectAssignedAgent(wc.getId())).isFalse();
        verify(acdService).releaseAssignment(eq(wc.getId()), anyString());
        verify(agentLegDialer, never()).originateAgentLeg(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A5: connect originate failure releases the ACD assignment — no orphan reservation")
    void connectOriginateFailureReleasesAssignment() {
        QueueWaitingCall wc = new QueueWaitingCall();
        wc.setId(UUID.randomUUID());
        wc.setCallSessionId(SESSION_ID);
        wc.setStatus(QueueWaitingCallStatus.ASSIGNED);
        wc.setAssignedAgentId(AGENT_ID);
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(wc.getId()))
                .thenReturn(Optional.of(wc));
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                SESSION_ID, CallLegType.AGENT)).thenReturn(List.of());
        CallSession s = session(CallSessionStatus.ANSWERED);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(s));
        stubAgent(AGENT_ID);
        when(callLegRepository.save(any(CallLeg.class))).thenAnswer(inv -> inv.getArgument(0));
        when(agentLegDialer.originateAgentLeg(any(), any(), any(), any()))
                .thenThrow(new com.shivang.obd.telephony.EslException("reject"));

        assertThat(service.connectAssignedAgent(wc.getId())).isTrue();
        verify(acdService).releaseAssignment(eq(wc.getId()), anyString());
        // Caller stays in the queue flow for re-assignment.
        assertThat(s.getStatus()).isEqualTo(CallSessionStatus.ANSWERED);
    }

    // NOTE: agent-leg events (ringing/answer/hangup) and CHANNEL_BRIDGE
    // confirmations are correlated by the agent channel UUID in
    // EslEventService and delegate to the SHARED AgentConnectEvents
    // boundary regardless of session kind — covered by AgentEslRoutingTest.
    // There is deliberately no inbound-specific agent-hangup handler.
}
