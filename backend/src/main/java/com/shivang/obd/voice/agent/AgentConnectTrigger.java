package com.shivang.obd.voice.agent;

import java.util.UUID;

/**
 * CONNECT_BY_AGENT entry boundary (VB-3).
 * <p>
 * The DTMF layer requests a connection through this seam; the agent
 * connection service ({@code ConnectByAgentService}) owns everything after
 * that — selection, reservation, leg creation, originate, bridge. The DTMF
 * layer never touches agent state directly.
 * <p>
 * Idempotent: requesting a connect for a session that already has an agent
 * leg returns the existing connection state without selecting/reserving
 * again. Optional bean — contexts without the agent layer run without it.
 */
public interface AgentConnectTrigger {

    /**
     * Attempts to connect the caller on {@code callSessionId} to an eligible
     * agent (atomic reservation + agent leg originate).
     *
     * @return explainable outcome (selected agent or rejection reason)
     */
    AgentEligibility connectByAgent(UUID callSessionId, UUID attemptId);

    /**
     * VB-7A: attempts the same connection, honouring a campaign's frozen
     * CONNECT_BY_AGENT configuration.
     *
     * <p>Declared {@code default} and delegating to
     * {@link #connectByAgent(UUID, UUID)} so the meaning of the existing
     * two-argument request is <em>unchanged</em>: an implementation that has not
     * been taught about campaign configuration keeps the tenant-wide VB-3
     * selection exactly as before, and a DTMF/IVR campaign whose terminal action
     * is {@code CONNECT_BY_AGENT} — which has no CONNECT_BY_AGENT type config at
     * all — is unaffected.
     *
     * <p>A request naming a queue must reach the queue/ACD authority, never a
     * direct scan: queue membership, candidate order and the reservation stay
     * owned by ACD, so a non-member agent can never be selected.
     *
     * @param request the frozen per-call parameters; {@code null} is treated as
     *                {@link AgentConnectRequest#unscoped()}
     */
    default AgentEligibility connectByAgent(
            UUID callSessionId, UUID attemptId, AgentConnectRequest request) {
        return connectByAgent(callSessionId, attemptId);
    }
}
