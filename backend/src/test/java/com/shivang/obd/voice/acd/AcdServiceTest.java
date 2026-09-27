package com.shivang.obd.voice.acd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.voice.agent.Agent;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentAvailability;
import com.shivang.obd.voice.agent.AgentEndpointEntity;
import com.shivang.obd.voice.agent.AgentReasons;
import com.shivang.obd.voice.agent.AgentRepository;
import com.shivang.obd.voice.agent.AgentReservation;
import com.shivang.obd.voice.agent.AgentReservationRepository;
import com.shivang.obd.voice.agent.AgentReservationService;
import com.shivang.obd.voice.agent.AgentReservationStatus;
import com.shivang.obd.voice.call.EndpointType;
import com.shivang.obd.voice.queue.Queue;
import com.shivang.obd.voice.queue.QueueMemberStatus;
import com.shivang.obd.voice.queue.QueueMembership;
import com.shivang.obd.voice.queue.QueueMembershipRepository;
import com.shivang.obd.voice.queue.QueueRepository;
import com.shivang.obd.voice.queue.QueueStatus;
import com.shivang.obd.voice.queue.QueueWaitingCall;
import com.shivang.obd.voice.queue.QueueWaitingCallRepository;
import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-4C unit tests: queue/waiting-call/agent eligibility, deterministic
 * selection ordering, reservation-vs-assignment race handling,
 * idempotency and tenant isolation at the service boundary. PostgreSQL
 * concurrency (advisory locks, conditional UPDATEs) is proven by the
 * integration suite — mocks here only shape the deterministic logic.
 */
class AcdServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID TENANT_B =
            UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");

    private QueueRepository queueRepository;
    private QueueMembershipRepository membershipRepository;
    private QueueWaitingCallRepository waitingCallRepository;
    private AgentRepository agentRepository;
    private com.shivang.obd.voice.agent.AgentEndpointRepository endpointRepository;
    private AgentReservationService reservationService;
    private AgentReservationRepository reservationRepository;
    private AcdService service;

    private Queue queue;
    private QueueWaitingCall waitingCall;

    @BeforeEach
    void setUp() {
        queueRepository = mock(QueueRepository.class);
        membershipRepository = mock(QueueMembershipRepository.class);
        waitingCallRepository = mock(QueueWaitingCallRepository.class);
        agentRepository = mock(AgentRepository.class);
        endpointRepository = mock(com.shivang.obd.voice.agent.AgentEndpointRepository.class);
        reservationService = mock(AgentReservationService.class);
        reservationRepository = mock(AgentReservationRepository.class);

        service = new AcdService(queueRepository, membershipRepository,
                waitingCallRepository, agentRepository, endpointRepository,
                reservationService, reservationRepository);

        queue = new Queue();
        queue.setId(UUID.fromString("cccccccc-0000-4000-8000-000000000001"));
        queue.setTenantId(TENANT_A);
        queue.setName("Support");
        queue.setStatus(QueueStatus.ACTIVE);

        waitingCall = new QueueWaitingCall();
        waitingCall.setId(UUID.fromString("dddddddd-0000-4000-8000-000000000001"));
        waitingCall.setTenantId(TENANT_A);
        waitingCall.setQueueId(queue.getId());
        waitingCall.setCallSessionId(UUID.randomUUID());
        waitingCall.setStatus(QueueWaitingCallStatus.WAITING);
        waitingCall.setEnteredAt(Instant.now());
    }

    // === queue eligibility ===

    @Test
    @DisplayName("Q-EL-1: unknown queue → QUEUE_NOT_ELIGIBLE / QUEUE_NOT_FOUND")
    void unknownQueueRejected() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.empty());

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.QUEUE_NOT_ELIGIBLE);
        assertThat(result.reason()).isEqualTo(AcdReasons.QUEUE_NOT_FOUND);
    }

    @Test
    @DisplayName("Q-EL-2: foreign-tenant queue → fail closed QUEUE_NOT_FOUND")
    void foreignQueueFailClosed() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_B))
                .thenReturn(Optional.empty());

        var result = service.attemptAssignment(TENANT_B, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.QUEUE_NOT_ELIGIBLE);
        assertThat(result.reason()).isEqualTo(AcdReasons.QUEUE_NOT_FOUND);
    }

    @Test
    @DisplayName("Q-EL-3: INACTIVE queue → QUEUE_NOT_ACTIVE; no agent selected")
    void inactiveQueueRejected() {
        queue.setStatus(QueueStatus.INACTIVE);
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.QUEUE_NOT_ELIGIBLE);
        assertThat(result.reason()).isEqualTo(AcdReasons.QUEUE_NOT_ACTIVE);
        verify(reservationService, never()).reserve(any(), any(), any(), any());
    }

    // === waiting-call eligibility ===

    @Test
    @DisplayName("W-EL-1: unknown waiting call → WAITING_CALL_NOT_ELIGIBLE")
    void unknownWaitingCallRejected() {
        stubQueue();
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(waitingCall.getId()))
                .thenReturn(Optional.empty());

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.WAITING_CALL_NOT_ELIGIBLE);
        assertThat(result.reason()).isEqualTo(AcdReasons.WAITING_CALL_NOT_FOUND);
    }

    @Test
    @DisplayName("W-EL-2: waiting call from another queue → rejected (not silently assigned)")
    void waitingCallFromOtherQueueRejected() {
        stubQueue();
        waitingCall.setQueueId(UUID.randomUUID());
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(waitingCall.getId()))
                .thenReturn(Optional.of(waitingCall));

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.WAITING_CALL_NOT_ELIGIBLE);
    }

    @Test
    @DisplayName("W-EL-3: terminal (ABANDONED) call → WAITING_CALL_NOT_WAITING")
    void terminalWaitingCallRejected() {
        stubQueue();
        waitingCall.setStatus(QueueWaitingCallStatus.ABANDONED);
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(waitingCall.getId()))
                .thenReturn(Optional.of(waitingCall));

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.reason()).isEqualTo(AcdReasons.WAITING_CALL_NOT_WAITING);
        verify(reservationService, never()).reserve(any(), any(), any(), any());
    }

    @Test
    @DisplayName("W-EL-4: ASSIGNED call with live reservation → ALREADY_ASSIGNED (idempotent replay)")
    void assignedCallReplaysIdempotently() {
        stubQueue();
        UUID reservationId = UUID.randomUUID();
        waitingCall.setStatus(QueueWaitingCallStatus.ASSIGNED);
        waitingCall.setAssignedAgentId(UUID.randomUUID());
        waitingCall.setAssignedReservationId(reservationId);
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(waitingCall.getId()))
                .thenReturn(Optional.of(waitingCall));
        AgentReservation live = new AgentReservation();
        live.setId(reservationId);
        live.setStatus(AgentReservationStatus.RESERVED);
        when(reservationRepository.findByIdAndTenantIdAndDeletedAtIsNull(reservationId, TENANT_A))
                .thenReturn(Optional.of(live));

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.ALREADY_ASSIGNED);
        assertThat(result.reservationId()).isEqualTo(reservationId);
        verify(reservationService, never()).reserve(any(), any(), any(), any());
        verify(waitingCallRepository, never()).returnToWaiting(any());
    }

    @Test
    @DisplayName("W-EL-5: ASSIGNED call whose reservation died → marker repaired to WAITING")
    void staleAssignedMarkerRepaired() {
        stubQueue();
        UUID reservationId = UUID.randomUUID();
        waitingCall.setStatus(QueueWaitingCallStatus.ASSIGNED);
        waitingCall.setAssignedReservationId(reservationId);
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(waitingCall.getId()))
                .thenReturn(Optional.of(waitingCall));
        AgentReservation dead = new AgentReservation();
        dead.setId(reservationId);
        dead.setStatus(AgentReservationStatus.RELEASED);
        when(reservationRepository.findByIdAndTenantIdAndDeletedAtIsNull(reservationId, TENANT_A))
                .thenReturn(Optional.of(dead));
        when(waitingCallRepository.returnToWaiting(waitingCall.getId())).thenReturn(1);

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.WAITING_CALL_NOT_ELIGIBLE);
        verify(waitingCallRepository).returnToWaiting(waitingCall.getId());
    }

    // === membership + agent eligibility ===

    @Test
    @DisplayName("M-EL-1: no ACTIVE memberships → NO_ACTIVE_MEMBERS")
    void noActiveMembers() {
        stubQueueAndCall();
        QueueMembership inactive = membership(UUID.randomUUID(), QueueMemberStatus.INACTIVE);
        when(membershipRepository.findByQueueIdAndTenantIdAndDeletedAtIsNull(
                queue.getId(), TENANT_A)).thenReturn(List.of(inactive));

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.NO_ELIGIBLE_AGENT);
        assertThat(result.reason()).isEqualTo(AcdReasons.NO_ACTIVE_MEMBERS);
    }

    @Test
    @DisplayName("A-EL-1: suspended member rejected with AGENT_UNAVAILABLE")
    void suspendedMemberRejected() {
        stubQueueAndCall();
        Agent agent = agent(AgentAdminStatus.SUSPENDED, AgentAvailability.AVAILABLE, true);
        stubMembership(agent);
        when(agentRepository.findAllById(any())).thenReturn(List.of(agent));

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.NO_ELIGIBLE_AGENT);
        assertThat(result.rejectedCandidates())
                .extracting(AcdResult.RejectedCandidate::reason)
                .containsExactly(AgentReasons.AGENT_UNAVAILABLE);
    }

    @Test
    @DisplayName("A-EL-2: OFFLINE member rejected with AGENT_OFFLINE")
    void offlineMemberRejected() {
        stubQueueAndCall();
        Agent agent = agent(AgentAdminStatus.ACTIVE, AgentAvailability.OFFLINE, true);
        stubMembership(agent);
        when(agentRepository.findAllById(any())).thenReturn(List.of(agent));

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.rejectedCandidates())
                .extracting(AcdResult.RejectedCandidate::reason)
                .containsExactly("AGENT_OFFLINE");
    }

    @Test
    @DisplayName("A-EL-3: member without a dialable endpoint rejected with AGENT_ENDPOINT_INVALID")
    void memberWithoutEndpointRejected() {
        stubQueueAndCall();
        Agent agent = agent(AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, false);
        stubMembership(agent);
        when(agentRepository.findAllById(any())).thenReturn(List.of(agent));

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.rejectedCandidates())
                .extracting(AcdResult.RejectedCandidate::reason)
                .containsExactly(AgentReasons.AGENT_ENDPOINT_INVALID);
        verify(reservationService, never()).reserve(any(), any(), any(), any());
    }

    // === selection ===

    @Test
    @DisplayName("S-1: least-loaded member selected first; tie broken by agent id")
    void leastLoadThenIdOrdering() {
        stubQueueAndCall();
        Agent a1 = agent(UUID.fromString("eeeeeeee-0000-4000-8000-000000000002"),
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, true);
        Agent a2 = agent(UUID.fromString("eeeeeeee-0000-4000-8000-000000000001"),
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, true);
        stubMembership(a1, a2);
        when(agentRepository.findAllById(any())).thenReturn(List.of(a1, a2));
        // both eligible; equal load → lower UUID wins deterministically
        when(reservationRepository.countActiveByAgentId(any())).thenReturn(2);
        when(reservationService.reserve(any(), any(), any(), any()))
                .thenAnswer(inv -> Optional.of(hold(inv.getArgument(0))));
        when(waitingCallRepository.claimAssignment(any(), any(), any())).thenReturn(1);

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.ASSIGNED);
        assertThat(result.agentId()).isEqualTo(a2.getId()); // stable id tie-break
    }

    @Test
    @DisplayName("S-2: same state + same inputs → same selected candidate (determinism)")
    void deterministicRepeat() {
        stubQueueAndCall();
        Agent a1 = agent(UUID.fromString("eeeeeeee-0000-4000-8000-00000000000a"),
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, true);
        Agent a2 = agent(UUID.fromString("eeeeeeee-0000-4000-8000-00000000000b"),
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, true);
        stubMembership(a1, a2);
        when(agentRepository.findAllById(any())).thenReturn(List.of(a2, a1));
        when(reservationRepository.countActiveByAgentId(a1.getId())).thenReturn(0);
        when(reservationRepository.countActiveByAgentId(a2.getId())).thenReturn(1);
        when(reservationService.reserve(any(), any(), any(), any()))
                .thenAnswer(inv -> Optional.of(hold(inv.getArgument(0))));
        when(waitingCallRepository.claimAssignment(any(), any(), any())).thenReturn(1);

        var first = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());
        var second = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(first.agentId()).isEqualTo(a1.getId());
        assertThat(second.agentId()).isEqualTo(a1.getId());
    }

    // === reservation + assignment ===

    @Test
    @DisplayName("R-1: reservation lost on first candidate → re-evaluate next; no premature NO_AGENT")
    void raceLossReevaluatesNextCandidate() {
        stubQueueAndCall();
        Agent a1 = agent(UUID.fromString("eeeeeeee-0000-4000-8000-00000000000a"),
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, true);
        Agent a2 = agent(UUID.fromString("eeeeeeee-0000-4000-8000-00000000000b"),
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, true);
        stubMembership(a1, a2);
        when(agentRepository.findAllById(any())).thenReturn(List.of(a1, a2));
        when(reservationRepository.countActiveByAgentId(any())).thenReturn(0);
        when(reservationService.reserve(eq(a1.getId()), any(), any(), any()))
                .thenReturn(Optional.empty()); // race lost / at capacity
        when(reservationService.reserve(eq(a2.getId()), any(), any(), any()))
                .thenAnswer(inv -> Optional.of(hold(a2.getId())));
        when(waitingCallRepository.claimAssignment(any(), any(), any())).thenReturn(1);

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.ASSIGNED);
        assertThat(result.agentId()).isEqualTo(a2.getId());
        assertThat(result.rejectedCandidates())
                .extracting(AcdResult.RejectedCandidate::reason)
                .contains(AgentReasons.AGENT_BUSY);
    }

    @Test
    @DisplayName("R-2: all candidates lose the race → deterministic AGENT_BUSY; call stays WAITING")
    void allRacesLostLeavesCallWaiting() {
        stubQueueAndCall();
        Agent a1 = agent(AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, true);
        stubMembership(a1);
        when(agentRepository.findAllById(any())).thenReturn(List.of(a1));
        when(reservationRepository.countActiveByAgentId(any())).thenReturn(0);
        when(reservationService.reserve(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.NO_ELIGIBLE_AGENT);
        assertThat(result.reason()).isEqualTo(AgentReasons.AGENT_BUSY);
        // the waiting call was NOT moved (§32)
        verify(waitingCallRepository, never()).claimAssignment(any(), any(), any());
        assertThat(waitingCall.getStatus()).isEqualTo(QueueWaitingCallStatus.WAITING);
    }

    @Test
    @DisplayName("R-3: happy path → ASSIGNED with ACD ownership + expiry stamped on the hold")
    void happyPathStampsOwnershipAndExpiry() {
        stubQueueAndCall();
        Agent a1 = agent(AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, true);
        stubMembership(a1);
        when(agentRepository.findAllById(any())).thenReturn(List.of(a1));
        when(reservationRepository.countActiveByAgentId(any())).thenReturn(0);
        AgentReservation hold = hold(a1.getId());
        when(reservationService.reserve(eq(a1.getId()), any(), any(), any()))
                .thenReturn(Optional.of(hold));
        when(waitingCallRepository.claimAssignment(any(), any(), any())).thenReturn(1);

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.ASSIGNED);
        assertThat(hold.getQueueId()).isEqualTo(queue.getId());
        assertThat(hold.getWaitingCallId()).isEqualTo(waitingCall.getId());
        assertThat(hold.getExpiresAt()).isAfter(Instant.now());
        verify(reservationRepository).save(hold);
        verify(waitingCallRepository).claimAssignment(waitingCall.getId(),
                a1.getId(), hold.getId());
    }

    @Test
    @DisplayName("R-4: assignment claim lost → fresh hold unwound, ALREADY_ASSIGNED returned")
    void assignmentClaimLostUnwindsHold() {
        stubQueueAndCall();
        Agent a1 = agent(AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, true);
        stubMembership(a1);
        when(agentRepository.findAllById(any())).thenReturn(List.of(a1));
        when(reservationRepository.countActiveByAgentId(any())).thenReturn(0);
        AgentReservation hold = hold(a1.getId());
        when(reservationService.reserve(any(), any(), any(), any()))
                .thenReturn(Optional.of(hold));
        when(waitingCallRepository.claimAssignment(any(), any(), any())).thenReturn(0);
        when(waitingCallRepository.findById(waitingCall.getId()))
                .thenReturn(Optional.of(waitingCall));

        var result = service.attemptAssignment(TENANT_A, queue.getId(), waitingCall.getId());

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.ALREADY_ASSIGNED);
        verify(reservationService).releaseForCallSession(
                waitingCall.getCallSessionId(), AcdReasons.ASSIGNMENT_LOST);
    }

    // === release ===

    @Test
    @DisplayName("REL-1: releaseAssignment → hold released + call back to WAITING")
    void releaseAssignmentReturnsCallToWaiting() {
        waitingCall.setStatus(QueueWaitingCallStatus.ASSIGNED);
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(waitingCall.getId()))
                .thenReturn(Optional.of(waitingCall));
        when(reservationRepository.releaseByWaitingCall(
                waitingCall.getId(), "TEST")).thenReturn(1);
        when(waitingCallRepository.returnToWaiting(waitingCall.getId())).thenReturn(1);

        boolean released = service.releaseAssignment(waitingCall.getId(), "TEST");

        assertThat(released).isTrue();
        verify(waitingCallRepository).returnToWaiting(waitingCall.getId());
    }

    @Test
    @DisplayName("REL-2: releaseAssignment on non-ASSIGNED call → no-op false (idempotent)")
    void releaseAssignmentIdempotent() {
        waitingCall.setStatus(QueueWaitingCallStatus.WAITING);
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(waitingCall.getId()))
                .thenReturn(Optional.of(waitingCall));

        boolean released = service.releaseAssignment(waitingCall.getId(), "TEST");

        assertThat(released).isFalse();
        verify(reservationRepository, never()).releaseByWaitingCall(any(), anyString());
    }

    // === overflow service ===

    @Test
    @DisplayName("OF-1: overflow not configured → no-op 0")
    void overflowNotConfiguredNoop() {
        var overflow = new AcdOverflowService(queueRepository, waitingCallRepository);
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));

        assertThat(overflow.moveWaitingCallsToOverflow(TENANT_A, queue.getId())).isZero();
        verify(waitingCallRepository, never()).moveWaitingCallsToQueue(any(), any(), any());
    }

    @Test
    @DisplayName("OF-2: overflow executes single bounded hop to the configured target")
    void overflowSingleHop() {
        var overflow = new AcdOverflowService(queueRepository, waitingCallRepository);
        Queue target = new Queue();
        target.setId(UUID.randomUUID());
        target.setTenantId(TENANT_A);
        target.setStatus(QueueStatus.ACTIVE);
        queue.setOverflowEnabled(true);
        queue.setOverflowQueueId(target.getId());
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(target.getId(), TENANT_A))
                .thenReturn(Optional.of(target));
        when(waitingCallRepository.moveWaitingCallsToQueue(
                queue.getId(), target.getId(), TENANT_A)).thenReturn(3);

        assertThat(overflow.moveWaitingCallsToOverflow(TENANT_A, queue.getId())).isEqualTo(3);
        // exactly one move call — no recursion into the target
        verify(waitingCallRepository).moveWaitingCallsToQueue(
                queue.getId(), target.getId(), TENANT_A);
    }

    @Test
    @DisplayName("OF-3: DISABLED overflow target → skipped (fail safe)")
    void overflowDisabledTargetSkipped() {
        var overflow = new AcdOverflowService(queueRepository, waitingCallRepository);
        Queue target = new Queue();
        target.setId(UUID.randomUUID());
        target.setTenantId(TENANT_A);
        target.setStatus(QueueStatus.DISABLED);
        queue.setOverflowEnabled(true);
        queue.setOverflowQueueId(target.getId());
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(target.getId(), TENANT_A))
                .thenReturn(Optional.of(target));

        assertThat(overflow.moveWaitingCallsToOverflow(TENANT_A, queue.getId())).isZero();
    }

    // === helpers ===

    private void stubQueue() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
    }

    private void stubQueueAndCall() {
        stubQueue();
        when(waitingCallRepository.findByIdAndDeletedAtIsNull(waitingCall.getId()))
                .thenReturn(Optional.of(waitingCall));
    }

    private void stubMembership(Agent... agents) {
        List<QueueMembership> memberships = new java.util.ArrayList<>();
        for (Agent a : agents) {
            memberships.add(membership(a.getId(), QueueMemberStatus.ACTIVE));
        }
        when(membershipRepository.findByQueueIdAndTenantIdAndDeletedAtIsNull(
                queue.getId(), TENANT_A)).thenReturn(memberships);
    }

    private QueueMembership membership(UUID agentId, QueueMemberStatus status) {
        QueueMembership m = new QueueMembership();
        m.setId(UUID.randomUUID());
        m.setQueueId(queue.getId());
        m.setAgentId(agentId);
        m.setTenantId(TENANT_A);
        m.setStatus(status);
        return m;
    }

    private Agent agent(AgentAdminStatus adminStatus, AgentAvailability availability,
                        boolean withEndpoint) {
        return agent(UUID.randomUUID(), adminStatus, availability, withEndpoint);
    }

    private Agent agent(UUID agentId, AgentAdminStatus adminStatus,
                        AgentAvailability availability, boolean withEndpoint) {
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setTenantId(TENANT_A);
        agent.setDisplayName("Agent");
        agent.setAdminStatus(adminStatus);
        agent.setAvailability(availability);
        agent.setMaxConcurrentCalls(1);
        if (withEndpoint) {
            AgentEndpointEntity endpoint = new AgentEndpointEntity();
            endpoint.setId(UUID.randomUUID());
            endpoint.setAgentId(agent.getId());
            endpoint.setTenantId(TENANT_A);
            endpoint.setEndpointType(EndpointType.SIP);
            endpoint.setDialTarget("sip:agent@pbx.example");
            endpoint.setEnabled(true);
            when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                    agent.getId(), TENANT_A)).thenReturn(List.of(endpoint));
        } else {
            when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                    agent.getId(), TENANT_A)).thenReturn(List.of());
        }
        return agent;
    }

    private AgentReservation hold(UUID agentId) {
        AgentReservation r = new AgentReservation();
        r.setId(UUID.randomUUID());
        r.setAgentId(agentId);
        r.setTenantId(TENANT_A);
        r.setCallSessionId(waitingCall.getCallSessionId());
        r.setStatus(AgentReservationStatus.RESERVED);
        r.setReservedAt(Instant.now());
        return r;
    }
}
