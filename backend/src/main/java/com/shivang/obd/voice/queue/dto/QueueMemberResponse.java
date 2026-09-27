package com.shivang.obd.voice.queue.dto;

import com.shivang.obd.voice.queue.QueueMemberStatus;
import java.time.Instant;
import java.util.UUID;

/** Membership response (VB-4B). */
public record QueueMemberResponse(
    UUID id,
    UUID queueId,
    UUID agentId,
    UUID tenantId,
    QueueMemberStatus status,
    Instant createdAt,
    Instant updatedAt
) {
}
