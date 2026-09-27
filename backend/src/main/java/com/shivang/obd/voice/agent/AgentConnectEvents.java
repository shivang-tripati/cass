package com.shivang.obd.voice.agent;

import com.shivang.obd.voice.call.CallLeg;

/**
 * Agent-connect event boundary (VB-3).
 * <p>
 * The telephony event service ({@code EslEventService}) detects ESL events
 * whose Call-UUID matches an AGENT-type {@link CallLeg} and delegates them
 * here; the implementation ({@code ConnectByAgentService}) owns the agent
 * connect lifecycle (answer → bridge → hangup cleanup). FreeSWITCH specifics
 * stay behind the media boundary — no raw ESL in the connect service.
 * <p>
 * All methods must be idempotent and must not throw: duplicate progress /
 * answer / hangup events are no-ops.
 */
public interface AgentConnectEvents {

    /** The agent leg is ringing (CHANNEL_PROGRESS on the agent channel). */
    void onAgentLegRinging(CallLeg agentLeg);

    /** The agent answered (CHANNEL_ANSWER on the agent channel) — bridge now. */
    void onAgentLegAnswered(CallLeg agentLeg);

    /**
     * The agent channel hung up (CHANNEL_HANGUP on the agent channel).
     * Before the bridge this fails the connect attempt; after the bridge the
     * caller leg is torn down and the reservation released.
     *
     * @param hangupCause the FreeSWITCH hangup cause (may be null)
     */
    void onAgentLegHangup(CallLeg agentLeg, String hangupCause);

    /**
     * The caller hung up at any point of the connect flow (selection,
     * ringing, answering, bridging, active). Cancels the agent operation,
     * releases the agent reservation and terminates the agent leg.
     */
    void onCallerHangup(java.util.UUID callSessionId);

    /**
     * FreeSWITCH confirmed the caller↔agent bridge (CHANNEL_BRIDGE — the
     * authoritative confirmation event, not command acceptance). Establishes
     * BRIDGED on the session and both legs and marks the agent reservation
     * ACTIVE. Idempotent: duplicate confirmations are no-ops.
     *
     * @param callSessionId the bridged call session
     * @param agentUuid     the agent channel UUID (Bridge-B-Unique-ID)
     */
    void onBridgeConfirmed(java.util.UUID callSessionId, String agentUuid);
}
