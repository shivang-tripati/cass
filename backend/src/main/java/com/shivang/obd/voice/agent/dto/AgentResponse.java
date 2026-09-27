package com.shivang.obd.voice.agent.dto;

import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentAvailability;
import java.time.Instant;
import java.util.UUID;

/**
 * Agent response (VB-4A).
 *
 * @param id                 agent id
 * @param tenantId           owning tenant
 * @param displayName        human-readable name
 * @param adminStatus        administrative status (allowed to participate?)
 * @param availability       runtime presence (can take a call right now?)
 * @param maxConcurrentCalls concurrency budget
 * @param userId             optional linked AGENT-role user
 * @param activeCallCount    live agent-leg count derived from canonical
 *                           call data (CallLeg) — never an independent
 *                           mutable counter
 * @param createdAt          creation timestamp
 * @param updatedAt          last update timestamp
 */
public record AgentResponse(
    UUID id,
    UUID tenantId,
    String displayName,
    AgentAdminStatus adminStatus,
    AgentAvailability availability,
    int maxConcurrentCalls,
    UUID userId,
    int activeCallCount,
    Instant createdAt,
    Instant updatedAt
) {
}
