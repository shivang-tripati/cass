package com.shivang.obd.voice.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create-agent request (VB-4A).
 *
 * @param displayName        human-readable name (operations/audit)
 * @param maxConcurrentCalls agent concurrency budget (1..50)
 * @param userId             optional link to the AGENT-role user operating
 *                           this agent (identity boundary kept optional)
 */
public record CreateAgentRequest(
    @NotBlank @Size(max = 120) String displayName,
    @Min(1) @Max(50) Integer maxConcurrentCalls,
    UUID userId
) {
}
