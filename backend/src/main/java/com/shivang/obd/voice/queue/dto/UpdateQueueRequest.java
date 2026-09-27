package com.shivang.obd.voice.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Update-queue request (VB-4B). All fields optional; only supplied fields
 * are applied. Overflow rules are validated on every change: an enabled
 * overflow requires a same-tenant target queue that is not the queue
 * itself; disabling overflow clears the stored target.
 *
 * @param name             new queue name (unique per tenant)
 * @param description      new description (null = unchanged)
 * @param maxWaitingCalls  configured waiting capacity
 * @param maxWaitSeconds   configured wait-time budget in seconds
 * @param overflowEnabled  whether overflow is configured
 * @param overflowQueueId  new overflow target (same tenant, not self)
 */
public record UpdateQueueRequest(
    @Size(max = 120) String name,
    @Size(max = 500) String description,
    @Min(0) @Max(100000) Integer maxWaitingCalls,
    @Min(0) @Max(86400) Integer maxWaitSeconds,
    Boolean overflowEnabled,
    UUID overflowQueueId
) {
}
