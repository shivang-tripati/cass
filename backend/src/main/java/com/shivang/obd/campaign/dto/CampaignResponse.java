package com.shivang.obd.campaign.dto;

import tools.jackson.databind.JsonNode;
import com.shivang.obd.campaign.CampaignRunMode;
import com.shivang.obd.campaign.CampaignStatus;
import com.shivang.obd.campaign.CampaignType;
import com.shivang.obd.campaign.ContentMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record CampaignResponse(
    UUID id,
    UUID tenantId,
    String name,
    String description,
    CampaignType campaignType,
    CampaignRunMode runMode,
    CampaignStatus status,
    Integer version,
    UUID clonedFromCampaignId,
    UUID contactGroupId,
    UUID didId,
    ContentMode contentMode,
    UUID audioAssetId,
    UUID ttsTemplateId,
    ScheduleConfig schedule,
    RetryPolicyConfig retryPolicy,
    JsonNode typeConfig,
    JsonNode integrationConfig,
    Instant createdAt,
    Instant updatedAt,
    Boolean callOnWhitelistNumbers,

    /**
     * Optional campaign-specific Voice Blast daily dial limit. Valid
     * values are 1-3. Null uses the platform maximum of 3.
     */
    @Schema(description = "Optional campaign-specific Voice Blast daily dial limit. "
        + "Valid values are 1-3. Null uses the platform maximum of 3.",
        example = "2")
    Integer dailyDialLimit
) {
}
