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
}
