package com.shivang.obd.voice.acd;

import java.util.List;
import java.util.UUID;

/**
 * Deterministic, explainable ACD decision result (VB-4C).
 *
 * <p>VB-4C stops at the assignment boundary: {@code ASSIGNED} means a
 * waiting call + queue + agent + live reservation exist — NOT a telephony
 * connection. VB-4D consumes this result; no CallLeg/provider id is ever
 * created here.</p>
 *
 * @param status             outcome status
 * @param queueId            queue evaluated
 * @param waitingCallId      waiting call evaluated
 * @param agentId            reserved agent (ASSIGNED/ALREADY_ASSIGNED only)
 * @param reservationId      live reservation backing the assignment
 * @param reason             machine-readable primary reason
 * @param rejectedCandidates per-candidate rejection detail (explainability)
 */
public record AcdResult(
        AcdStatus status,
        UUID queueId,
        UUID waitingCallId,
        UUID agentId,
        UUID reservationId,
        String reason,
        List<RejectedCandidate> rejectedCandidates) {

    /** Per-candidate rejection detail (audit/debug; safe identifiers only). */
    public record RejectedCandidate(UUID agentId, String reason) {
    }

    /** ACD outcome statuses. */
    public enum AcdStatus {
        /** Agent atomically reserved and the waiting call claimed. */
        ASSIGNED,
        /** The call already had a live assignment — idempotent replay. */
        ALREADY_ASSIGNED,
        /** Queue-level eligibility failed (not found / not active / tenant mismatch). */
        QUEUE_NOT_ELIGIBLE,
        /** Waiting-call eligibility failed (not found / not WAITING / tenant mismatch). */
        WAITING_CALL_NOT_ELIGIBLE,
        /** No candidate could be reserved (none eligible, all busy, or race lost). */
        NO_ELIGIBLE_AGENT,
        /** Over-capacity waiting calls were moved to the configured overflow queue. */
        OVERFLOWED
    }

    public static AcdResult assigned(UUID queueId, UUID waitingCallId,
                                     UUID agentId, UUID reservationId,
                                     List<RejectedCandidate> rejected) {
        return new AcdResult(AcdStatus.ASSIGNED, queueId, waitingCallId,
                agentId, reservationId,
                com.shivang.obd.voice.agent.AgentReasons.SELECTED, rejected);
    }

    public static AcdResult alreadyAssigned(UUID queueId, UUID waitingCallId,
                                            UUID agentId, UUID reservationId) {
        return new AcdResult(AcdStatus.ALREADY_ASSIGNED, queueId, waitingCallId,
                agentId, reservationId, "ALREADY_ASSIGNED", List.of());
    }

    public static AcdResult failed(AcdStatus status, UUID queueId,
                                   UUID waitingCallId, String reason,
                                   List<RejectedCandidate> rejected) {
        return new AcdResult(status, queueId, waitingCallId, null, null,
                reason, rejected);
    }
}
