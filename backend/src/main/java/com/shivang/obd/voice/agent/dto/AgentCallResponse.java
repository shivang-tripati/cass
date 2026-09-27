package com.shivang.obd.voice.agent.dto;

import java.util.UUID;

/**
 * Response for an accepted agent outbound call — canonical call
 * identities only (no second call-ID model).
 */
public record AgentCallResponse(
        UUID callSessionId,
        UUID agentLegId,
        UUID customerLegId,
        UUID agentId,
        String destinationNumber
) {
}
