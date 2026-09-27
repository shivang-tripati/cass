package com.shivang.obd.campaign.dto;

import com.shivang.obd.campaign.CampaignExecutionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Campaign execution response.
 *
 * @param configurationSnapshotId the immutable configuration snapshot this
 *        execution owns (VB-6A correction); always present — executions are
 *        created with their snapshot in the same transaction
 */
public record CampaignExecutionResponse(
    UUID id,
    UUID campaignId,
    UUID tenantId,
    CampaignExecutionStatus status,
    String idempotencyKey,
    UUID configurationSnapshotId,
    Instant requestedAt,
    String requestedBy,
    Instant startedAt,
    Instant completedAt,
    String failureReason
) {
}