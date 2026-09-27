package com.shivang.obd.voice.agent.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One historical agent call (VB-4A) — derived from the canonical
 * CallSession/CallLeg model. Only terminal legs qualify as historical.
 *
 * @param callSessionId  owning session
 * @param callLegId      the agent leg
 * @param sessionStatus  final session status
 * @param legStatus      final leg status
 * @param legType        agent leg type
 * @param direction      session direction
 * @param remoteTarget   customer-side number/target of the session
 * @param failureCode    standardized failure code (when failed)
 * @param failureReason  human-readable failure reason (when failed)
 * @param initiatedAt    leg initiation timestamp
 * @param answeredAt     leg answer timestamp
 * @param endedAt        leg end timestamp
 */
public record AgentCallHistoryResponse(
    UUID callSessionId,
    UUID callLegId,
    String sessionStatus,
    String legStatus,
    String legType,
    String direction,
    String remoteTarget,
    String failureCode,
    String failureReason,
    Instant initiatedAt,
    Instant answeredAt,
    Instant endedAt
) {
}
