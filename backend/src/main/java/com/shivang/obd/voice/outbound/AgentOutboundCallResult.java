package com.shivang.obd.voice.outbound;

import java.util.UUID;

/**
 * Accepted agent outbound call — canonical call identities only
 * (no second call-ID model; the session/leg IDs ARE the call).
 */
public record AgentOutboundCallResult(
        UUID callSessionId,
        UUID agentLegId,
        UUID customerLegId,
        UUID agentId,
        String destinationNumber
) {
    public static AgentOutboundCallResult accepted(
            UUID callSessionId, UUID agentLegId, UUID customerLegId,
            UUID agentId, String destinationNumber) {
        return new AgentOutboundCallResult(
                callSessionId, agentLegId, customerLegId, agentId, destinationNumber);
    }
}
