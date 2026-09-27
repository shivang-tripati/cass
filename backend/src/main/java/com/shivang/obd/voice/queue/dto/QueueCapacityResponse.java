package com.shivang.obd.voice.queue.dto;

import java.util.UUID;

/**
 * Queue capacity read model (VB-4B). Derived from configuration plus the
 * live WAITING row count — a report of current state, not an admission
 * decision (admission/dispatch policy belongs to VB-4C/4D).
 */
public record QueueCapacityResponse(
    UUID queueId,
    int configuredCapacity,
    long currentWaiting,
    long remainingCapacity
) {
}
