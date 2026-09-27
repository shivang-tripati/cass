package com.shivang.obd.campaign.dto;

import com.shivang.obd.campaign.CallAttemptStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Call attempt response.
 */
public record CallAttemptResponse(
    UUID id,
    UUID executionId,
    UUID campaignId,
    UUID tenantId,
    UUID contactId,
    UUID didId,
    Integer attemptNumber,
    CallAttemptStatus status,
    Instant scheduledAt,
    Instant startedAt,
    Instant completedAt,
    String failureCode,
    String failureReason,
    Instant createdAt,
    Instant updatedAt
) {
}