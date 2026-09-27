package com.shivang.obd.voice.queue.dto;

import com.shivang.obd.voice.queue.QueueStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Queue response (VB-4B). Configuration values are reported as stored;
 * no execution semantics are implied.
 */
public record QueueResponse(
    UUID id,
    UUID tenantId,
    String name,
    String description,
    QueueStatus status,
    int maxWaitingCalls,
    int maxWaitSeconds,
    boolean overflowEnabled,
    UUID overflowQueueId,
    Instant createdAt,
    Instant updatedAt
) {
}
