package com.shivang.obd.voice.agent;

import java.util.UUID;

/**
 * Queue-scoped agent assignment, as a port (VB-7A).
 *
 * <h2>Why this is an interface</h2>
 *
 * <p>Queue eligibility, membership-gated candidate selection, the deterministic
 * order and the atomic reservation are all owned by
 * {@code com.shivang.obd.voice.acd.AcdService} (VB-4C), which lives in
 * {@code voice.acd} — an internal package of the {@code voice} module. The
 * campaign side must not reach it directly: {@code voice.acd} is not a named
 * interface, and widening the module surface to the whole ACD + queue CRUD
 * packages would expose far more than a campaign needs.
 *
 * <p>This is the same port-inversion the codebase already uses three times
 * ({@code DtmfCollectorTrigger}, {@code PlaybackTrigger},
 * {@code IvrPromptChecker}): the consumer-side seam is declared in
 * {@code voice.agent} — the package whose own package-info already designates it
 * "the canonical voice agent API" for campaign orchestration — and the
 * authoritative implementation stays inside the voice domain.
 *
 * <h2>What the implementation must do</h2>
 *
 * <p>Delegate to {@code AcdService}. It must NOT re-implement candidate
 * selection, ordering, or reservation, and it must NOT select an agent that is
 * not an active member of the named queue. An outbound CONNECT_BY_AGENT call is
 * represented as an ordinary {@code queue_waiting_calls} row — the canonical
 * waiting-call model, whose {@code call_session_id} already references
 * {@code call_sessions} and which duplicates no call attributes — so ACD's
 * membership gating, assignment claim and idempotent replay all apply unchanged.
 */
public interface AgentQueueAssignmentTrigger {

    /**
     * Attempts to reserve an eligible agent from {@code queueId} for a call
     * that is already on the line.
     *
     * <p>Idempotent per {@code callSessionId}: a call that already holds an
     * ACD assignment replays as the existing assignment rather than reserving a
     * second agent.
     *
     * @param tenantId      the call's own tenant (server-derived); a queue from
     *                      any other tenant is treated as nonexistent
     * @param queueId       the campaign-configured queue
     * @param callSessionId the answered outbound call
     * @return the decision; never throws for a domain outcome
     */
    AgentQueueAssignment assignToQueue(
            UUID tenantId, UUID queueId, UUID callSessionId);

    /**
     * VB-7A: records that a queue-scoped call has finished, so the
     * {@code queue_waiting_calls} row enrolled for it is left in a terminal
     * state rather than {@code ASSIGNED} forever.
     *
     * <p>Declared {@code default} and a no-op, so an implementation that has not
     * been taught this is unaffected. Nothing about eligibility depends on it:
     * every ACD sweep reads only {@code WAITING} rows or {@code RESERVED}
     * reservations, so an {@code ASSIGNED} row is already inert. This is
     * bookkeeping accuracy, not a correctness dependency.
     *
     * @param callSessionId  the call that has finished
     * @param callCompleted  {@code true} when the call was bridged and completed
     *                      downstream, {@code false} when it ended without
     *                      completing
     */
    default void closeQueuePresence(UUID callSessionId, boolean callCompleted) {
    }
}
