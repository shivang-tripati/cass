package com.shivang.obd.campaign.dto;

import com.shivang.obd.campaign.CampaignDailyAttempts;
import com.shivang.obd.campaign.MaxCallDurationSeconds;
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
    Integer dailyDialLimit,

    /**
     * Optional campaign-specific ceiling on daily campaign attempts for one
     * contact. Null clears the override and restores the platform default.
     * Distinct from {@code dailyDialLimit} (provider-accepted dials per DNID).
     */
    @CampaignDailyAttempts
    @Schema(description = "Optional campaign-specific ceiling on daily campaign "
        + "ATTEMPTS for one contact (1-10). Null clears the override so the platform "
        + "default of 10 applies. This is NOT the dailyDialLimit field, which caps "
        + "provider-ACCEPTED dials per contact per actual DNID at 3. A value above the "
        + "platform maximum is rejected. Frozen into the execution snapshot, so editing "
        + "the campaign later does not change a running execution's ceiling.",
        minimum = "1", maximum = "10", example = "4")
    Integer maxDailyAttempts,

    @MaxCallDurationSeconds
    @Schema(description = "Optional maximum lifetime of an ESTABLISHED outbound call, in "
        + "seconds (1-3600). Null (omitted) uses the platform default of 300 seconds (5 "
        + "minutes). This is NOT a ring timeout, NOT a provider connection timeout and NOT a "
        + "playback length: it bounds the active call session from the moment the provider "
        + "reports the channel was answered. When exceeded, the platform terminates the "
        + "session and the attempt fails with MAX_DURATION_EXCEEDED, which the campaign retry "
        + "policy may then govern because the subscriber was genuinely called. Frozen into the "
        + "execution snapshot, so editing the campaign later does not change a running "
        + "execution's call duration.",
        minimum = "1", maximum = "3600", example = "180")
    Integer maxCallDurationSeconds
) {

    /**
     * The pre-VB-6D.3 shape: no daily-attempt override, so the platform
     * default applies. Retained so existing construction sites keep their
     * exact previous meaning.
     */
    public UpdateCampaignRequest(
            String name,
            String description,
            CampaignRunMode runMode,
            UUID contactGroupId,
            UUID didId,
            ContentMode contentMode,
            UUID audioAssetId,
            UUID ttsTemplateId,
            ScheduleConfig schedule,
            RetryPolicyConfig retryPolicy,
            JsonNode typeConfig,
            JsonNode integrationConfig,
            Integer dailyDialLimit) {
        this(name, description, runMode, contactGroupId, didId, contentMode, audioAssetId,
                ttsTemplateId, schedule, retryPolicy, typeConfig, integrationConfig,
                dailyDialLimit, null, null);
    }

    /**
     * The pre-VB-6E shape: no maximum call duration, so the platform default
     * of 300 seconds applies. Retained so existing construction sites keep
     * their exact previous meaning.
     */
    public UpdateCampaignRequest(
            String name,
            String description,
            CampaignRunMode runMode,
            UUID contactGroupId,
            UUID didId,
            ContentMode contentMode,
            UUID audioAssetId,
            UUID ttsTemplateId,
            ScheduleConfig schedule,
            RetryPolicyConfig retryPolicy,
            JsonNode typeConfig,
            JsonNode integrationConfig,
            Integer dailyDialLimit,
            Integer maxDailyAttempts) {
        this(name, description, runMode, contactGroupId, didId, contentMode, audioAssetId,
                ttsTemplateId, schedule, retryPolicy, typeConfig, integrationConfig,
                dailyDialLimit, maxDailyAttempts, null);
    }
}
