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

    /**
     * Required for DTMF and CONNECT_BY_AGENT; non-empty JSON object.
     *
     * <p>VB-7A: for {@code campaignType = CONNECT_BY_AGENT} this is a
     * <b>typed, required</b> object — the VB-6A placeholder that accepted any
     * non-empty payload is gone, and an unrecognised field is rejected with 400.
     */
    @Schema(description = "Type-specific configuration, required for DTMF and CONNECT_BY_AGENT.\n\n"
        + "**CONNECT_BY_AGENT** requires exactly this shape (any other field is rejected):\n"
        + "- `connectByAgent.queueId` (string, UUID, required) — an existing queue owned by this\n"
        + "  campaign's tenant. The queue is the source of truth for agent membership and\n"
        + "  eligibility; the campaign stores only the reference.\n"
        + "- `connectByAgent.selectionStrategy` (string, required) — only\n"
        + "  `LEAST_ACTIVE_RESERVATIONS` is supported, the existing deterministic order\n"
        + "  (fewest active reservations, then agent id). No round-robin, weighted, skills\n"
        + "  or AI routing exists.\n"
        + "- `connectByAgent.ringDurationSeconds` (integer, required, 10-240) — how long the\n"
        + "  answered call rings its reserved agent before AGENT_NO_ANSWER.\n\n"
        + "A foreign-tenant queue is reported exactly like a nonexistent one. Live agent\n"
        + "availability is NOT a readiness condition: a campaign stays ready when the queue\n"
        + "has nobody free right now. The queue's administrative status IS: an inactive or\n"
        + "disabled queue makes the campaign unready and unschedulable.\n\n"
        + "**MISSED_CALL** requires exactly this shape (any other field is rejected):\n"
        + "- `missedCall.ringDurationSeconds` (integer, required, 10-60) — the whole time\n"
        + "  budget. Before answer it is the maximum ringing time; after answer the deadline\n"
        + "  is rebased onto the answer instant, so an answered call is bounded too and never\n"
        + "  waits for stale recovery. A MISSED_CALL campaign plays no media, collects no\n"
        + "  input, and involves no agent or queue. DID, audience, retry, schedule and safety\n"
        + "  are the campaign's own fields, not part of this payload.\n\n"
        + "When the budget elapses the platform ends the call itself. That is a successful\n"
        + "delivery: the attempt completes with no failure code and is not retried. A\n"
        + "carrier-reported no-answer remains an ordinary retryable NO_ANSWER, and a\n"
        + "subscriber answering is a runtime event, not a configuration problem.\n\n"
        + "Example: {\"connectByAgent\":{\"queueId\":\"3f2504e0-4f89-11d3-9a0c-0305e82c3301\","
        + "\"selectionStrategy\":\"LEAST_ACTIVE_RESERVATIONS\",\"ringDurationSeconds\":60}}",
        example = "{\"connectByAgent\": {\"queueId\": \"3f2504e0-4f89-11d3-9a0c-0305e82c3301\", "
            + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
            + "\"ringDurationSeconds\": 60}}")
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
