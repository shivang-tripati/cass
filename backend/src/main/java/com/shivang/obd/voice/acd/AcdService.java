package com.shivang.obd.voice.acd;

import com.shivang.obd.voice.agent.Agent;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentReasons;
import com.shivang.obd.voice.agent.AgentReservation;
import com.shivang.obd.voice.agent.AgentReservationRepository;
import com.shivang.obd.voice.agent.AgentReservationService;
import com.shivang.obd.voice.agent.AgentAvailability;
import com.shivang.obd.voice.queue.Queue;
import com.shivang.obd.voice.queue.QueueMemberStatus;
import com.shivang.obd.voice.queue.QueueMembershipRepository;
import com.shivang.obd.voice.queue.QueueRepository;
import com.shivang.obd.voice.queue.QueueStatus;
import com.shivang.obd.voice.queue.QueueWaitingCall;
import com.shivang.obd.voice.queue.QueueWaitingCallRepository;
import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-4C ACD engine — control-plane decision and reservation layer.
 *
 * <p><b>Boundary:</b> ACD determines and reserves an agent for a waiting
 * call. It performs NO telephony — no {@code EslClient}, no legs, no
 * originate, no bridge, no provider ids. VB-4D consumes
 * {@link AcdResult} and owns FreeSWITCH orchestration.</p>
 *
 * <p><b>Flow (per §16 of the phase contract):</b> selection is advisory,
 * reservation is authoritative.
 * <ol>
 *   <li>queue eligibility (exists / same tenant / ACTIVE)</li>
 *   <li>waiting-call eligibility (WAITING / belongs to the queue /
 *       same tenant); an ASSIGNED call with a live reservation replays
 *       idempotently as {@code ALREADY_ASSIGNED}</li>
 *   <li>candidate scan over ACTIVE memberships (VB-4A availability
 *       semantics: ACTIVE admin status, AVAILABLE presence, dialable
 *       endpoint, capacity derived from live reservation counts)</li>
 *   <li>deterministic order (VB-3 rule): least active reservations ASC,
 *       then agent id ASC — same state always picks the same agent</li>
 *   <li>atomic reservation via the VB-3 service (advisory lock +
 *       re-read + conditional capacity check)</li>
 *   <li>assignment claim: WAITING→ASSIGNED conditional UPDATE — exactly
 *       one racing attempt wins; losers unwind their fresh hold and
 *       re-evaluate remaining candidates (bounded, no loops)</li>
 * </ol></p>
 *
 * <p><b>Waiting calls never disappear on a failed attempt</b> (§32): a
 * NO_ELIGIBLE_AGENT result leaves the call WAITING. Only the configured
 * timeout (→ ABANDONED) or overflow (bounded single hop, no loops)
 * policies move it — see {@link AcdMaintenanceScheduler}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AcdService {

    /** Scan window for the candidate query (same spirit as VB-3's scan limit). */
    private static final int CANDIDATE_SCAN_LIMIT = 100;

    /** How long an ACD reservation holds before the reconciler releases it. */
    static final Duration RESERVATION_TTL = Duration.ofMinutes(5);

    private final QueueRepository queueRepository;
    private final QueueMembershipRepository membershipRepository;
    private final QueueWaitingCallRepository waitingCallRepository;
    private final com.shivang.obd.voice.agent.AgentRepository agentRepository;
    private final com.shivang.obd.voice.agent.AgentEndpointRepository endpointRepository;
    private final AgentReservationService reservationService;
    private final AgentReservationRepository reservationRepository;

    /**
     * Attempts assignment of one waiting call.
     *
     * @param tenantId       caller's tenant scope (fail-closed vs the queue's)
     * @param queueId        queue to assign from
     * @param waitingCallId  waiting call to assign
     * @param rejectedSink   optional sink collecting per-candidate rejections
     * @return deterministic, explainable result (never throws for domain outcomes)
     */
    @Transactional
    public AcdResult attemptAssignment(
            UUID tenantId, UUID queueId, UUID waitingCallId,
            List<AcdResult.RejectedCandidate> rejectedSink) {

        // 1. Queue eligibility — tenant-scoped lookup (fail closed).
        Queue queue = queueRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(queueId, tenantId)
                .orElse(null);
        if (queue == null) {
            return AcdResult.failed(AcdResult.AcdStatus.QUEUE_NOT_ELIGIBLE,
                    queueId, waitingCallId, AcdReasons.QUEUE_NOT_FOUND, List.of());
        }
        if (queue.getStatus() != QueueStatus.ACTIVE) {
            return AcdResult.failed(AcdResult.AcdStatus.QUEUE_NOT_ELIGIBLE,
                    queueId, waitingCallId, AcdReasons.QUEUE_NOT_ACTIVE, List.of());
        }

        // 2. Waiting-call eligibility — must belong to THIS queue and tenant.
        QueueWaitingCall waitingCall = waitingCallRepository
                .findByIdAndDeletedAtIsNull(waitingCallId).orElse(null);
        if (waitingCall == null
                || !waitingCall.getTenantId().equals(tenantId)
                || !waitingCall.getQueueId().equals(queueId)) {
            return AcdResult.failed(AcdResult.AcdStatus.WAITING_CALL_NOT_ELIGIBLE,
                    queueId, waitingCallId, AcdReasons.WAITING_CALL_NOT_FOUND, List.of());
        }
        if (waitingCall.getStatus() == QueueWaitingCallStatus.ASSIGNED) {
            return alreadyAssignedResult(waitingCall);
        }
        if (waitingCall.getStatus() != QueueWaitingCallStatus.WAITING) {
            return AcdResult.failed(AcdResult.AcdStatus.WAITING_CALL_NOT_ELIGIBLE,
                    queueId, waitingCallId, AcdReasons.WAITING_CALL_NOT_WAITING, List.of());
        }

        // 3. Membership-gated candidate scan (deterministic order).
        List<UUID> memberAgentIds = membershipRepository
                .findByQueueIdAndTenantIdAndDeletedAtIsNull(queueId, tenantId)
                .stream()
                .filter(m -> m.getStatus() == QueueMemberStatus.ACTIVE)
                .map(com.shivang.obd.voice.queue.QueueMembership::getAgentId)
                .toList();
        if (memberAgentIds.isEmpty()) {
            return AcdResult.failed(AcdResult.AcdStatus.NO_ELIGIBLE_AGENT,
                    queueId, waitingCallId, AcdReasons.NO_ACTIVE_MEMBERS, List.of());
        }

        List<Rejected> rejected = new ArrayList<>();
        List<UUID> ordered = orderCandidates(memberAgentIds, rejected);
        if (ordered.isEmpty()) {
            return AcdResult.failed(AcdResult.AcdStatus.NO_ELIGIBLE_AGENT,
                    queueId, waitingCallId, AcdReasons.NO_ELIGIBLE_AGENT,
                    toList(rejected));
        }

        // 4. Selection is advisory; reservation is authoritative. On a
        //    reservation race loss, re-evaluate the NEXT candidate — the
        //    loser must not see NO_AGENT while another member is free.
        for (UUID agentId : ordered) {
            Optional<AgentReservation> hold = reservationService.reserve(
                    agentId, tenantId, waitingCall.getCallSessionId(), null);
            if (hold.isEmpty()) {
                rejected.add(new Rejected(agentId, AgentReasons.AGENT_BUSY));
                continue; // race lost / at capacity — deterministic re-evaluation
            }

            // Stamp ACD ownership + bounded TTL on the fresh hold.
            AgentReservation reservation = hold.get();
            reservation.setQueueId(queueId);
            reservation.setWaitingCallId(waitingCallId);
            reservation.setExpiresAt(Instant.now().plus(RESERVATION_TTL));
            reservationRepository.save(reservation);

            // Assignment claim: atomic WAITING→ASSIGNED. The conditional
            // UPDATE is the race boundary for duplicate assignment.
            int claimed = waitingCallRepository.claimAssignment(
                    waitingCallId, agentId, reservation.getId());
            if (claimed == 0) {
                // Concurrent ACD attempt assigned this call first. Unwind
                // the fresh hold (idempotent release) and re-evaluate — a
                // second waiting call must still find this agent usable on
                // its own attempt, but THIS call is done: someone else owns it.
                reservationService.releaseForCallSession(
                        waitingCall.getCallSessionId(), AcdReasons.ASSIGNMENT_LOST);
                return AcdResult.alreadyAssigned(queueId, waitingCallId,
                        agentIdFromClaim(waitingCallId), null);
            }
            log.info("ACD assignment (queue={}, waitingCall={}, agent={}, reservation={})",
                    queueId, waitingCallId, agentId, reservation.getId());
            return AcdResult.assigned(queueId, waitingCallId, agentId,
                    reservation.getId(), toList(rejected));
        }

        // Every candidate lost the reservation race or is at capacity.
        return AcdResult.failed(AcdResult.AcdStatus.NO_ELIGIBLE_AGENT,
                queueId, waitingCallId, AgentReasons.AGENT_BUSY, toList(rejected));
    }

    /**
     * Convenience overload without a rejection sink.
     */
    @Transactional
    public AcdResult attemptAssignment(UUID tenantId, UUID queueId, UUID waitingCallId) {
        return attemptAssignment(tenantId, queueId, waitingCallId, null);
    }

    /**
     * Releases the reservation backing an ACD assignment and returns the
     * waiting call to WAITING so future ACD attempts can process it
     * (assignment no longer viable: agent became invalid, queue disabled,
     * or VB-4D reports the connection failed before telephony started).
     * Idempotent: an already-released reservation or non-ASSIGNED call is
     * a no-op.
     */
    @Transactional
    public boolean releaseAssignment(UUID waitingCallId, String reason) {
        QueueWaitingCall waitingCall = waitingCallRepository
                .findByIdAndDeletedAtIsNull(waitingCallId).orElse(null);
        if (waitingCall == null
                || waitingCall.getStatus() != QueueWaitingCallStatus.ASSIGNED) {
            return false;
        }
        // Conditional release of the ACD hold (matches on waiting_call_id;
        // idempotent — already-RELEASED rows update 0 rows). The
        // reservation lifecycle owns the agent's BUSY→AVAILABLE flip.
        int released = reservationRepository.releaseByWaitingCall(waitingCallId, reason);
        if (released > 0) {
            // Return to WAITING (conditional — another thread may have moved it).
            waitingCallRepository.returnToWaiting(waitingCallId);
            log.info("ACD assignment released (waitingCall={}, reason={})",
                    waitingCallId, reason);
        }
        return released > 0;
    }

    // === helpers ===

    private AcdResult alreadyAssignedResult(QueueWaitingCall waitingCall) {
        // Idempotent replay: report the live assignment if the reservation
        // is still active; if the hold died (released/expired), the call
        // must be re-claimable — repair the stale ASSIGNED marker.
        UUID reservationId = waitingCall.getAssignedReservationId();
        if (reservationId != null) {
            Optional<AgentReservation> existing = reservationRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(
                            reservationId, waitingCall.getTenantId());
            if (existing.isPresent()
                    && existing.get().getStatus()
                            != com.shivang.obd.voice.agent.AgentReservationStatus.RELEASED) {
                return AcdResult.alreadyAssigned(waitingCall.getQueueId(),
                        waitingCall.getId(), waitingCall.getAssignedAgentId(),
                        reservationId);
            }
            // Reservation gone: revert the marker so ACD can re-assign.
            waitingCallRepository.returnToWaiting(waitingCall.getId());
        }
        return AcdResult.failed(AcdResult.AcdStatus.WAITING_CALL_NOT_ELIGIBLE,
                waitingCall.getQueueId(), waitingCall.getId(),
                AcdReasons.WAITING_CALL_NOT_WAITING, List.of());
    }

    private UUID agentIdFromClaim(UUID waitingCallId) {
        return waitingCallRepository.findById(waitingCallId)
                .map(QueueWaitingCall::getAssignedAgentId)
                .orElse(null);
    }

    /**
     * Filters member agents through VB-4A eligibility semantics and
     * returns them in the deterministic selection order (VB-3 rule):
     * least active reservations ASC, then agent id ASC. Load is derived
     * from the canonical reservation table — no cached counters.
     */
    private List<UUID> orderCandidates(
            List<UUID> memberAgentIds, List<Rejected> rejected) {
        List<Agent> members = agentRepository.findAllById(memberAgentIds).stream()
                .filter(a -> a.getDeletedAt() == null)
                .toList();

        // membership → agent rows; a member row without a live agent row is
        // a data inconsistency — treat as rejected, not a crash.
        Map<UUID, Agent> byId = new LinkedHashMap<>();
        for (UUID memberId : memberAgentIds) {
            Agent agent = members.stream()
                    .filter(a -> a.getId().equals(memberId))
                    .findFirst().orElse(null);
            if (agent == null) {
                rejected.add(new Rejected(memberId, AgentReasons.AGENT_UNAVAILABLE));
                continue;
            }
            byId.put(memberId, agent);
        }

        List<UUID> result = new ArrayList<>();
        for (Map.Entry<UUID, Agent> entry : byId.entrySet()) {
            Agent agent = entry.getValue();
            if (agent.getAdminStatus() != AgentAdminStatus.ACTIVE) {
                rejected.add(new Rejected(agent.getId(), AgentReasons.AGENT_UNAVAILABLE));
                continue;
            }
            if (agent.getAvailability() != AgentAvailability.AVAILABLE) {
                rejected.add(new Rejected(agent.getId(),
                        agent.getAvailability() == AgentAvailability.OFFLINE
                                ? "AGENT_OFFLINE" : AgentReasons.AGENT_BUSY));
                continue;
            }
            boolean dialable = !endpointRepository
                    .findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                            agent.getId(), agent.getTenantId()).isEmpty();
            if (!dialable) {
                rejected.add(new Rejected(agent.getId(),
                        AgentReasons.AGENT_ENDPOINT_INVALID));
                continue;
            }
            result.add(agent.getId());
        }

        // Deterministic ordering — load (active holds) then stable id.
        return result.stream()
                .sorted((a, b) -> {
                    int loadA = reservationRepository.countActiveByAgentId(a);
                    int loadB = reservationRepository.countActiveByAgentId(b);
                    int byLoad = Integer.compare(loadA, loadB);
                    return byLoad != 0 ? byLoad : a.compareTo(b);
                })
                .toList();
    }

    private List<AcdResult.RejectedCandidate> toList(List<Rejected> rejected) {
        return rejected.stream()
                .map(r -> new AcdResult.RejectedCandidate(r.agentId(), r.reason()))
                .toList();
    }

    private record Rejected(UUID agentId, String reason) {
    }
}
