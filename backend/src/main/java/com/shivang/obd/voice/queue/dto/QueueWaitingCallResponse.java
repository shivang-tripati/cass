package com.shivang.obd.voice.queue.dto;

import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Waiting-call response (VB-4B). References the canonical call by
 * {@code callSessionId} only — call attributes (number, direction,
 * provider UUID, call status) live exclusively on CallSession/CallLeg.
 */
public record QueueWaitingCallResponse(
    UUID id,
    UUID queueId,
    UUID callSessionId,
    QueueWaitingCallStatus status,
    Instant enteredAt,
    Instant expiresAt
) {
}
