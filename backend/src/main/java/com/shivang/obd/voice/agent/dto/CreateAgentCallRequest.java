package com.shivang.obd.voice.agent.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/agents/{agentId}/calls} (VB-4E):
 * an authorized agent requests one outbound call to an external number.
 */
public record CreateAgentCallRequest(
        @NotBlank(message = "destination is required")
        String destination
) {
}
