package com.shivang.obd.campaign.dto;

import com.shivang.obd.campaign.DailyDialLimit;
import tools.jackson.databind.JsonNode;
import com.shivang.obd.campaign.CampaignRunMode;
import com.shivang.obd.campaign.ContentMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Campaign update request. PUT semantics: the configuration blocks are
 * replaced wholesale — omitting an optional block clears it. campaignType
 * is intentionally absent: it is immutable for the lifetime of a
 * campaign. Lifecycle changes go through the dedicated status operation,
 * and lineage (version/clonedFromCampaignId) is server-managed — neither
 * is accepted here.
 */
public record UpdateCampaignRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 5000) String description,
    CampaignRunMode runMode,
    UUID contactGroupId,
    UUID didId,

    // --- content selection (AUDIO xor TTS) ---
    ContentMode contentMode,
    UUID audioAssetId,
    UUID ttsTemplateId,

    @Valid ScheduleConfig schedule,
    @Valid RetryPolicyConfig retryPolicy,

    /** Required (non-empty JSON object) for DTMF and CONNECT_BY_AGENT. */
    JsonNode typeConfig,

    /** Optional API/webhook integration configuration; no secrets. */
    JsonNode integrationConfig,

    /**
     * Optional campaign-specific Voice Blast daily dial limit. Valid
     * values are 1-3. Null clears the explicit limit (platform maximum
     * of 3 applies).
     */
    @DailyDialLimit
    @Schema(description = "Optional campaign-specific Voice Blast daily dial limit. "
        + "Valid values are 1-3. Null uses the platform maximum of 3.",
        minimum = "1", maximum = "3",
        example = "2")
    Integer dailyDialLimit
) {
}
