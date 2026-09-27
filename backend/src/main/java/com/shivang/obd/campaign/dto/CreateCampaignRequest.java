package com.shivang.obd.campaign.dto;

import com.shivang.obd.campaign.CampaignDailyAttempts;
import com.shivang.obd.campaign.MaxCallDurationSeconds;
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
    Integer dailyDialLimit,

    /**
     * Optional campaign-specific ceiling on daily campaign attempts for one
     * contact. Distinct from {@code dailyDialLimit}: that field is the
     * DNID-scoped provider-accepted dial limit (max 3); this one bounds
     * dispatches per contact per day across every Voice Blast campaign of the
     * tenant. Null uses the platform default of 10.
     */
    @CampaignDailyAttempts
    @Schema(description = "Optional campaign-specific ceiling on daily campaign "
        + "ATTEMPTS for one contact (1-10). Null (omitted) uses the platform default "
        + "of 10. This is NOT the dailyDialLimit field: dailyDialLimit caps "
        + "provider-ACCEPTED dials per contact per actual DNID at 3, whereas this "
        + "caps DISPATCHES per contact per calendar day across all of the tenant's "
        + "Voice Blast campaigns, so rotating the DID cannot reset it. A value "
        + "above the platform maximum is rejected. Frozen into the execution "
        + "snapshot, so editing the campaign later does not change a running "
        + "execution's ceiling.",
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
     * The pre-VB-6D.3 shape: no daily-attempt override. Retained so existing
     * construction sites keep their exact previous meaning — a request built
     * this way means "use the platform default", because {@code null} is the
     * platform default.
     */
    public CreateCampaignRequest(
            String name,
            String description,
            CampaignType campaignType,
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
            Boolean callOnWhitelistNumbers,
            Integer dailyDialLimit) {
        this(name, description, campaignType, runMode, contactGroupId, didId, contentMode,
                audioAssetId, ttsTemplateId, schedule, retryPolicy, typeConfig,
                integrationConfig, callOnWhitelistNumbers, dailyDialLimit, null, null);
    }

    /**
     * The pre-VB-6E shape: no maximum call duration. Retained for the same
     * reason — a request built this way means "use the platform default of 300
     * seconds".
     */
    public CreateCampaignRequest(
            String name,
            String description,
            CampaignType campaignType,
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
            Boolean callOnWhitelistNumbers,
            Integer dailyDialLimit,
            Integer maxDailyAttempts) {
        this(name, description, campaignType, runMode, contactGroupId, didId, contentMode,
                audioAssetId, ttsTemplateId, schedule, retryPolicy, typeConfig,
                integrationConfig, callOnWhitelistNumbers, dailyDialLimit, maxDailyAttempts, null);
    }
}
