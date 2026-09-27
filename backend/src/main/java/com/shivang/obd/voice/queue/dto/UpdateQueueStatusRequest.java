package com.shivang.obd.voice.queue.dto;

import com.shivang.obd.voice.queue.QueueStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Queue lifecycle transition request (VB-4B). Valid transitions:
 * ACTIVE→INACTIVE, INACTIVE→ACTIVE, ACTIVE|INACTIVE→DISABLED.
 * DISABLED is terminal; same-state updates are idempotent.
 */
public record UpdateQueueStatusRequest(
    @NotNull QueueStatus status
) {
}
