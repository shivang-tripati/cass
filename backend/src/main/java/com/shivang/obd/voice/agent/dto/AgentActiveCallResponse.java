package com.shivang.obd.voice.agent.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One active agent call (VB-4A) — derived from the canonical
 * CallSession/CallLeg model, never a second source of truth.
 *
 * @param callSessionId     owning session
 * @param callLegId         the agent leg
 * @param sessionStatus     session lifecycle status
 * @param legStatus         agent-leg lifecycle status
 * @param legType           agent leg type (AGENT or EXTERNAL forwarding)
 * @param direction         session direction
 * @param remoteTarget      customer-side number/target of the session
 * @param providerCallId    FreeSWITCH UUID of the agent leg
 * @param initiatedAt       leg initiation timestamp
 * @param answeredAt        leg answer timestamp (null while ringing)
 */
public record AgentActiveCallResponse(
    UUID callSessionId,
    UUID callLegId,
    String sessionStatus,
    String legStatus,
    String legType,
    String direction,
    String remoteTarget,
    String providerCallId,
    Instant initiatedAt,
    Instant answeredAt
) {
}
