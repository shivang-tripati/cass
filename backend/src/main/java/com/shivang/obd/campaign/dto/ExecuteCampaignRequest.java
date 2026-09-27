package com.shivang.obd.campaign.dto;

import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request to create a campaign execution.
 */
public record ExecuteCampaignRequest(
    /**
     * Optional idempotency key. If provided, the same key for the same campaign
     * will not create duplicate executions.
     */
    @Size(max = 128) String idempotencyKey
) {
}