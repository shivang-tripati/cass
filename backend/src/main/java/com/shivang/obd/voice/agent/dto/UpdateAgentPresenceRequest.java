package com.shivang.obd.voice.agent.dto;

import com.shivang.obd.voice.agent.AgentAvailability;
import jakarta.validation.constraints.NotNull;

/**
 * Presence update request (VB-4A).
 *
 * <p>Only agent-declared presence states are accepted here:
 * {@code AVAILABLE} and {@code OFFLINE}. {@code BUSY} is owned by the
 * reservation lifecycle (VB-3) and is never agent-declared.</p>
 *
 * @param availability target presence
 */
public record UpdateAgentPresenceRequest(
    @NotNull AgentAvailability availability
) {
}
