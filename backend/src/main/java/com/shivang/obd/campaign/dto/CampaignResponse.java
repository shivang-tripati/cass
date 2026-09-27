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
    Integer dailyDialLimit,

    /**
     * Configured daily campaign-ATTEMPT ceiling; null means the platform
     * default. Distinct from {@code dailyDialLimit} (provider-accepted dials
     * per DNID).
     */
    @Schema(description = "Configured daily campaign-ATTEMPT ceiling per contact "
        + "per day across all of the tenant's Voice Blast campaigns (1-10). Null "
        + "means the platform default of 10 is in effect. Distinct from "
        + "dailyDialLimit, which caps provider-ACCEPTED dials per contact per actual "
        + "DNID at 3.",
        minimum = "1", maximum = "10", example = "4")
    Integer maxDailyAttempts,

    /**
     * VB-6E: configured maximum lifetime of an established outbound call, in
     * seconds; null means the platform default of 300s is in effect. Bounded
     * from answer, not a ring timeout and not a playback length.
     */
    @Schema(description = "Configured maximum lifetime of an ESTABLISHED outbound call in "
        + "seconds (1-3600), measured from the moment the provider reported answer. Null means "
        + "the platform default of 300 seconds is in effect. Not a ring timeout, not a provider "
        + "connection timeout and not a playback length. Exceeding it terminates the session and "
        + "fails the attempt with MAX_DURATION_EXCEEDED, which the campaign retry policy may "
        + "govern because the subscriber was genuinely called.",
        minimum = "1", maximum = "3600", example = "180")
    Integer maxCallDurationSeconds
) {

    /**
     * The pre-VB-6D.3 shape. Retained so existing construction sites keep
     * their exact previous meaning; {@code null} is reported as the platform
     * default, which is exactly what a campaign without an override stores.
     */
    public CampaignResponse(
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
            Integer dailyDialLimit) {
        this(id, tenantId, name, description, campaignType, runMode, status, version,
                clonedFromCampaignId, contactGroupId, didId, contentMode, audioAssetId,
                ttsTemplateId, schedule, retryPolicy, typeConfig, integrationConfig,
                createdAt, updatedAt, callOnWhitelistNumbers, dailyDialLimit, null, null);
    }

    /**
     * The pre-VB-6E shape: no maximum call duration, so the platform default
     * of 300 seconds is what a campaign built this way is running under.
     * Retained so existing construction sites keep their exact previous
     * meaning.
     */
    public CampaignResponse(
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
            Integer dailyDialLimit,
            Integer maxDailyAttempts) {
        this(id, tenantId, name, description, campaignType, runMode, status, version,
                clonedFromCampaignId, contactGroupId, didId, contentMode, audioAssetId,
                ttsTemplateId, schedule, retryPolicy, typeConfig, integrationConfig,
                createdAt, updatedAt, callOnWhitelistNumbers, dailyDialLimit, maxDailyAttempts,
                null);
    }
}
