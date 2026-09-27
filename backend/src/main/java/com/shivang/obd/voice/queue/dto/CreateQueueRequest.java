package com.shivang.obd.voice.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create-queue request (VB-4B). Capacity/timeout/overflow are persisted
 * configuration only — VB-4B executes neither timeout nor overflow.
 *
 * @param name             queue name, unique per tenant (live queues)
 * @param description      optional operational description
 * @param maxWaitingCalls  configured waiting capacity (0..100000)
 * @param maxWaitSeconds   configured wait-time budget in seconds (0..86400)
 * @param overflowEnabled  whether overflow is configured
 * @param overflowQueueId  overflow target; same tenant, never this queue
 */
public record CreateQueueRequest(
    @NotBlank @Size(max = 120) String name,
    @Size(max = 500) String description,
    @Min(0) @Max(100000) Integer maxWaitingCalls,
    @Min(0) @Max(86400) Integer maxWaitSeconds,
    Boolean overflowEnabled,
    UUID overflowQueueId
) {
}
