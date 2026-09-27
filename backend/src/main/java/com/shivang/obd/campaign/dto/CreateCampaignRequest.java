package com.shivang.obd.campaign.dto;

import com.shivang.obd.campaign.DailyDialLimit;
import tools.jackson.databind.JsonNode;
import com.shivang.obd.campaign.CampaignRunMode;
import com.shivang.obd.campaign.CampaignType;
import com.shivang.obd.campaign.ContentMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Campaign creation request. External references are plain UUIDs — their
 * existence/ownership/approval is validated by the owning module's
 * contract when available, and by the service-layer business rules.
 * runMode defaults to ONE_TIME when omitted. Lineage fields (version,
 * clonedFromCampaignId) are server-generated and deliberately absent.
 */
public record CreateCampaignRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 5000) String description,
    @NotNull CampaignType campaignType,
    CampaignRunMode runMode,
    UUID contactGroupId,
    UUID didId,

    // --- content selection (AUDIO xor TTS) ---
    ContentMode contentMode,
    UUID audioAssetId,
    UUID ttsTemplateId,

    @Valid ScheduleConfig schedule,
    @Valid RetryPolicyConfig retryPolicy,

    /** Required for DTMF and CONNECT_BY_AGENT; non-empty JSON object. */
    JsonNode typeConfig,

    /** Optional API/webhook integration configuration; no secrets. */
    JsonNode integrationConfig,

    /** When true, only numbers on tenant whitelist may be dialed (subject to higher blocks). */
    Boolean callOnWhitelistNumbers,

    /**
     * Optional campaign-specific Voice Blast daily dial limit. Valid
     * values are 1-3. Null (omitted) uses the platform maximum of 3.
     */
    @DailyDialLimit
    @Schema(description = "Optional campaign-specific Voice Blast daily dial limit. "
        + "Valid values are 1-3. Null uses the platform maximum of 3.",
        minimum = "1", maximum = "3",
        example = "2")
    Integer dailyDialLimit
) {
}
