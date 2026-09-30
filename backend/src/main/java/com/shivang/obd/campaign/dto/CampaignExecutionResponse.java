package com.shivang.obd.campaign.dto;

import com.shivang.obd.campaign.CampaignExecutionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Campaign execution response.
 *
 * @param configurationSnapshotId the immutable configuration snapshot this
 *        execution owns (VB-6A correction); always present - executions are
 *        created with their snapshot in the same transaction
 * @param deferredReason VB-8H (B10): why this execution has not started, and
 *        {@code null} whenever that question does not apply. It is
 *        <b>derived, never persisted</b>.
 *
 * <p>An execution can only be <em>created</em> while its campaign is ready, so
 * remaining {@code REQUESTED} has exactly one cause: the campaign has since left
 * the executable set, PAUSED being the ordinary case. It is deliberately null
 * for {@code RUNNING} and for every terminal status, so it can never be mistaken
 * for a failure reason, which {@code failureReason} owns.
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
    String failureReason,
    @Schema(description = "Why this execution has not started yet, or null when that "
            + "question does not apply. Derived on read and never persisted. An "
            + "execution can only be created while its campaign is ready, so remaining "
            + "REQUESTED has exactly one cause: the campaign has since left the "
            + "executable set (PAUSED being the ordinary case). Null for RUNNING and "
            + "for every terminal status, so it is never a second failureReason.")
    String deferredReason
) {
}
