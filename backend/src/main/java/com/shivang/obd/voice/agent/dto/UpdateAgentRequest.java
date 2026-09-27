package com.shivang.obd.voice.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Update-agent request (VB-4A). All fields optional; null leaves the
 * value unchanged. Lifecycle status is updated through its dedicated
 * endpoint, not this request.
 *
 * @param displayName        human-readable name
 * @param maxConcurrentCalls agent concurrency budget (1..50)
 * @param userId             optional identity link
 */
public record UpdateAgentRequest(
    @Size(min = 1, max = 120) String displayName,
    @Min(1) @Max(50) Integer maxConcurrentCalls,
    UUID userId
) {
}
