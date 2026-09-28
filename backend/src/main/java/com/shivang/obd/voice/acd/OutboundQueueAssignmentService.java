package com.shivang.obd.voice.acd;

import com.shivang.obd.voice.agent.AgentQueueAssignment;
import com.shivang.obd.voice.agent.AgentQueueAssignmentTrigger;
import com.shivang.obd.voice.queue.QueueWaitingCall;
import com.shivang.obd.voice.queue.QueueWaitingCallRepository;
import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AgentQueueAssignmentTrigger} implementation (VB-7A): an outbound,
 * already-answered call asks ACD for an agent from a configured queue.
 *
 * <h2>This is an adapter, not a second ACD</h2>
 *
 * <p>Every decision here belongs to {@link AcdService} (VB-4C) and is simply
 * delegated to it: queue existence and {@code ACTIVE} status, the
 * membership-gated candidate set, the deterministic order, the atomic
 * reservation, and the single-winner assignment claim. This class adds exactly
 * one thing, and it is bookkeeping rather than policy — representing an outbound
 * call in the waiting-call model {@link AcdService} already requires.
 *
 * <h2>Why the outbound call uses a queue_waiting_calls row at all</h2>
 *
 * <p>ACD's contract is deliberately queue-centric: it assigns a <em>waiting
 * call</em> to an agent, and it enforces that the waiting call belongs to the
 * same queue and tenant as the request. Rather than add a parallel "outbound"
 * assignment path with its own candidate scan and its own reservation call, an
 * outbound CONNECT_BY_AGENT call is enrolled in the canonical waiting-call model.
 * That table's {@code call_session_id} already references {@code call_sessions}
 * and it duplicates no call attributes, so the enrolment is a reference, not a
 * copy. Every ACD invariant then applies verbatim: a non-member agent can never
 * be selected, the reservation is the authoritative claim rather than the
 * selection, and a duplicate dispatch replays as the existing assignment.
 *
 * <h2>Single-shot by policy</h2>
 *
 * <p>An outbound campaign connect is a <em>decision now</em>: the callee is
 * already on the line, so waiting is not an option and the documented VB-3
 * policy is one deterministic attempt. The enrolled row is therefore never left
 * {@code WAITING} — on any non-assignment it is moved straight to
 * {@code REMOVED} in the same transaction, which also keeps it out of
 * {@code InboundAcdRetryScheduler}, the inbound loop that processes
 * {@code WAITING} rows and would otherwise dial an already-connected outbound
 * call through the inbound path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundQueueAssignmentService implements AgentQueueAssignmentTrigger {

    private final AcdService acdService;
    private final QueueWaitingCallRepository waitingCallRepository;

    @Override
    @Transactional
    public AgentQueueAssignment assignToQueue(UUID tenantId, UUID queueId, UUID callSessionId) {
        if (tenantId == null || queueId == null || callSessionId == null) {
            return AgentQueueAssignment.rejected(AcdReasons.QUEUE_NOT_FOUND);
        }

        // Enrol (idempotent): a repeat dispatch for the same call reuses the row
        // rather than creating a second one. The partial unique index on
        // (call_session_id) WHERE status = 'WAITING' is the race backstop.
        UUID waitingCallId = enroll(tenantId, queueId, callSessionId);

        AcdResult result = acdService.attemptAssignment(tenantId, queueId, waitingCallId);
        if (result.status() == AcdResult.AcdStatus.ASSIGNED
                || result.status() == AcdResult.AcdStatus.ALREADY_ASSIGNED) {
            log.info("Outbound queue assignment (queue={}, callSession={}, agent={}, status={})",
                    queueId, callSessionId, result.agentId(), result.status());
            return AgentQueueAssignment.assigned(result.agentId());
        }

        // No agent: this attempt is over. Take the row out of the waiting set so
        // the inbound retry sweep never picks up an outbound call, and so no
        // queue-timeout accounting is charged for a call that never waited.
        waitingCallRepository.markWaitingRemovedForSession(callSessionId);
        log.info("Outbound queue assignment declined (queue={}, callSession={}, status={}, reason={})",
                queueId, callSessionId, result.status(), result.reason());
        return AgentQueueAssignment.rejected(result.reason());
    }

    /**
     * Finds or creates the {@code WAITING} row representing this outbound call,
     * honouring the call's own tenant.
     *
     * <p>If a row already exists for the session it is reused whatever its
     * status, so an {@code ASSIGNED} row replays through ACD's idempotent
     * {@code ALREADY_ASSIGNED} path instead of being duplicated.
     */
    private UUID enroll(UUID tenantId, UUID queueId, UUID callSessionId) {
        QueueWaitingCall existing =
                waitingCallRepository.findByCallSessionIdAndDeletedAtIsNull(callSessionId)
                        .orElse(null);
        if (existing != null) {
            if (!existing.getTenantId().equals(tenantId)) {
                // Cannot happen through the campaign path, which always passes
                // the session's own tenant. Rejected rather than enrolled,
                // because creating a second row for the same session would
                // collide with the partial unique index.
                throw new IllegalStateException(
                        "Call session " + callSessionId + " is already enrolled in another tenant");
            }
            // Reused whatever its status, so an ASSIGNED row replays through
            // ACD's idempotent ALREADY_ASSIGNED path instead of being duplicated.
            return existing.getId();
        }
        QueueWaitingCall waitingCall = new QueueWaitingCall();
        waitingCall.setTenantId(tenantId);
        waitingCall.setQueueId(queueId);
        waitingCall.setCallSessionId(callSessionId);
        waitingCall.setStatus(QueueWaitingCallStatus.WAITING);
        return waitingCallRepository.save(waitingCall).getId();
    }

    /**
     * VB-7A: an {@code ASSIGNED} row is invisible to every sweep, so this is
     * bookkeeping accuracy rather than a correctness requirement. Moving it
     * keeps the queue's own history truthful and leaves nothing permanently
     * mid-flight.
     */
    @Override
    @Transactional
    public void closeQueuePresence(UUID callSessionId, boolean callCompleted) {
        if (callSessionId == null) {
            return;
        }
        waitingCallRepository.markAssignedTerminalForSession(
                callSessionId,
                callCompleted ? "COMPLETED" : "REMOVED");
    }
}
