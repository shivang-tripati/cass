package com.shivang.obd.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * Execution-affecting campaign configuration values (VB-6A correction) —
 * the payload of an immutable {@link CampaignExecutionConfiguration} row.
 * Mirrors the live {@link CampaignEntity} configuration columns that can
 * change the behavior of a running execution. Purely administrative
 * metadata (name, description, lifecycle status, lineage) is intentionally
 * NOT included.
 * <p>
 * Field semantics follow the VB-6A snapshot-boundary classification:
 * <ul>
 *   <li>SNAPSHOT_REQUIRED: type, audience/group reference, DID reference,
 *       content mode and asset/template references, schedule window, retry
 *       policy, typeConfig, whitelist enforcement flag, and - since VB-7C.3 -
 *       {@code integrationConfig}</li>
 * </ul>
 * Resource <em>validity</em> of the referenced DID/audio/TTS is never frozen:
 * every runtime check stays dynamic (VB-6A snapshot-vs-resource rule).
 *
 * <h2>The governing rule</h2>
 *
 * <p><b>Any campaign configuration consumed by execution-time runtime behaviour
 * MUST be captured in this snapshot in the same phase that introduces the first
 * runtime consumer.</b> This is a rule, not a caution. Shipping a consumer while
 * leaving its configuration mutable would let an operator edit a campaign's
 * endpoint or privacy level and silently change what an already-running
 * execution does - precisely the failure the immutable snapshot exists to
 * prevent.
 *
 * <p>It is enforced structurally rather than by convention. A runtime component
 * cannot obtain execution configuration except through
 * {@code CampaignRuntimeConfigResolver.CampaignRuntimeConfig}, which is built
 * solely from a frozen snapshot row; no API on this class, the resolver, or
 * their records returns a {@link CampaignEntity}. Reading the mutable campaign
 * from an execution-time path is therefore not a shortcut the API offers - it
 * requires reaching past the resolver to the repository.
 *
 * <h3>History of this boundary</h3>
 *
 * <p>VB-7C.2 introduced {@code integrationConfig} (webhook + report privacy)
 * with no runtime consumer and therefore excluded it from the snapshot,
 * deferring the decision to the first consumer under the rule above. That was
 * correct at the time: webhook delivery, signing and reporting were all
 * unimplemented, so freezing it would have recorded an execution's intent to
 * deliver webhooks - semantics no execution then had.
 *
 * <p>VB-7C.3 closed the boundary early and deliberately, before VB-8A execution
 * work begins, so the first delivery or reporting phase inherits an already-safe
 * snapshot instead of having to remember to extend it under deadline. Storage
 * followed the existing precedent exactly: one additive nullable column (V55), no
 * versioning, no history table, no compatibility shim. There is still no webhook
 * delivery and no reporting runtime - this froze configuration, it did not act
 * on it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Embeddable
public class CampaignConfigurationSnapshot {

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_type", nullable = false, length = 30)
    private CampaignType campaignType;

    /** Audience provenance: the contact group the execution targets. */
    @Column(name = "contact_group_id")
    private UUID contactGroupId;

    /** Requested caller-ID DID reference (validity stays runtime-checked). */
    @Column(name = "did_id")
    private UUID didId;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_mode", length = 10)
    private ContentMode contentMode;

    @Column(name = "audio_asset_id")
    private UUID audioAssetId;

    @Column(name = "tts_template_id")
    private UUID ttsTemplateId;

    // === Schedule window (same columns as campaigns, VB-6A snapshot) ===

    @Column(name = "schedule_start_date")
    private LocalDate scheduleStartDate;


    @Column(name = "daily_start_time")
    private LocalTime dailyStartTime;

    @Column(name = "daily_end_time")
    private LocalTime dailyEndTime;

    @Column(name = "timezone", length = 64)
    private String timezone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_days_of_week")
    private Set<DayOfWeek> allowedDaysOfWeek;

    @Column(name = "holiday_calendar_id")
    private UUID holidayCalendarId;

    // === Retry policy (same columns as campaigns) ===

    @Column(name = "retry_max_attempts", nullable = false)
    private Integer retryMaxAttempts;

    @Column(name = "retry_interval_seconds")
    private Integer retryIntervalSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "retry_strategy", nullable = false, length = 20)
    private RetryStrategy retryStrategy;

    /**
     * VB-6D.2: the campaign's per-category retry rules, frozen verbatim at
     * execution creation. {@code null} for campaigns that configure none, in
     * which case the flat retry fields above govern exactly as they did before
     * this phase.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retry_rules")
    private List<RetryRule> retryRules;

    /**
     * VB-6D.3: the campaign's configured daily ATTEMPT ceiling, frozen at
     * snapshot creation. Null means the platform default. Distinct from
     * {@link #dailyDialLimit}, which is the VB-6C provider-accepted dial
     * limit and is DNID-scoped.
     */
    @Column(name = "max_daily_attempts")
    private Integer maxDailyAttempts;

    /**
     * VB-6E: the campaign's configured maximum call duration in seconds,
     * frozen at snapshot creation. Null means the platform default of 300s.
     * Frozen for the same reason as every other execution-affecting field: a
     * later campaign edit must not change a running execution's call budget.
     */
    @Column(name = "max_call_duration_seconds")
    private Integer maxCallDurationSeconds;

    // === Type-specific and integration configuration ===

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "type_config")
    private JsonNode typeConfig;

    /**
     * VB-7C.3: the campaign's validated integration configuration - webhook and
     * report privacy - frozen at execution creation as its canonical JSON.
     *
     * <p><b>Why it is frozen now, before any consumer exists.</b> VB-7C.2
     * introduced this configuration with no runtime consumer and deliberately left
     * it out of the snapshot, recording a rule: the first runtime consumer must
     * add it in the same phase. VB-7C.3 closes that boundary <em>before</em> any
     * consumer exists, so the first delivery or reporting phase inherits an
     * already-safe snapshot rather than having to remember to extend it.
     *
     * <p><b>{@code null} is meaningful and is preserved verbatim.</b> It means
     * "this campaign configured no integration block", which is exactly what
     * {@code CampaignMapper} writes for a campaign that never configured one.
     * That is deliberately <em>not</em> the same as an explicit
     * {@code enabled=false} / {@code FULL} block, and is deliberately not
     * normalised into one: a runtime consumer must be able to tell "never
     * configured" from "configured to the default", and collapsing the two here
     * would erase that difference before anything could observe it. Read this
     * through {@link #asIntegrationConfig()} rather than casting the node.
     *
     * <p>The stored form is the <b>canonical validated</b> serialization produced
     * by {@code CampaignIntegrationConfig.toJson()}, exactly as
     * {@link #typeConfig} is - not whatever JSON the client originally sent.
     *
     * <p><b>One storage detail worth knowing.</b> "Canonical" describes the bytes
     * the platform writes; it does not describe the bytes the column returns.
     * {@code integration_config} is JSONB, and PostgreSQL normalises object key
     * order on write, so a reloaded node is content-identical to
     * {@code toJson()} but not text-identical. Array order <em>is</em> preserved,
     * which is what keeps the normalised, duplicate-free {@code events} list
     * deterministic. Nothing reads by key position - the typed model reads by key
     * name - so this is invisible to consumers. It is asserted deliberately in
     * {@code ExecutionSnapshotIntegrationConfigPostgresIntegrationTest} so the
     * behaviour is documented rather than discovered later.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "integration_config")
    private JsonNode integrationConfig;

    @Column(name = "call_on_whitelist_numbers", nullable = false)
    private Boolean callOnWhitelistNumbers;

    /**
     * The campaign's configured Voice Blast daily dial limit, frozen at
     * snapshot creation (VB-6C.2). Null is preserved verbatim — it means
     * "platform default"; the runtime service computes
     * {@code effectiveLimit(null) = 3}. Configured vs. effective stay
     * distinct by design (audit §8).
     */
    @Column(name = "daily_dial_limit")
    private Integer dailyDialLimit;

    /**
     * The VB-7C.2 shape: no integration configuration. Retained so every
     * existing construction site keeps its exact previous meaning — a snapshot
     * built this way has {@code integrationConfig == null}, which truthfully
     * means the campaign configured no integration block.
     */
    public CampaignConfigurationSnapshot(
            CampaignType campaignType,
            UUID contactGroupId,
            UUID didId,
            ContentMode contentMode,
            UUID audioAssetId,
            UUID ttsTemplateId,
            LocalDate scheduleStartDate,
            LocalTime dailyStartTime,
            LocalTime dailyEndTime,
            String timezone,
            Set<DayOfWeek> allowedDaysOfWeek,
            UUID holidayCalendarId,
            Integer retryMaxAttempts,
            Integer retryIntervalSeconds,
            RetryStrategy retryStrategy,
            List<com.shivang.obd.campaign.RetryRule> retryRules,
            Integer maxDailyAttempts,
            Integer maxCallDurationSeconds,
            JsonNode typeConfig,
            Boolean callOnWhitelistNumbers,
            Integer dailyDialLimit) {
        this(campaignType, contactGroupId, didId, contentMode, audioAssetId, ttsTemplateId,
                scheduleStartDate, dailyStartTime, dailyEndTime, timezone,
                allowedDaysOfWeek, holidayCalendarId, retryMaxAttempts, retryIntervalSeconds,
                retryStrategy, retryRules, maxDailyAttempts, maxCallDurationSeconds, typeConfig,
                null, callOnWhitelistNumbers, dailyDialLimit);
    }

    /**
     * The pre-VB-6D.2 shape: no per-category retry rules. Retained so every
     * existing construction site keeps its exact previous meaning — a
     * snapshot built this way has {@code retryRules == null}, which means the
     * flat retry fields govern.
     */
    public CampaignConfigurationSnapshot(
            CampaignType campaignType,
            UUID contactGroupId,
            UUID didId,
            ContentMode contentMode,
            UUID audioAssetId,
            UUID ttsTemplateId,
            LocalDate scheduleStartDate,
            LocalTime dailyStartTime,
            LocalTime dailyEndTime,
            String timezone,
            Set<DayOfWeek> allowedDaysOfWeek,
            UUID holidayCalendarId,
            Integer retryMaxAttempts,
            Integer retryIntervalSeconds,
            RetryStrategy retryStrategy,
            JsonNode typeConfig,
            Boolean callOnWhitelistNumbers,
            Integer dailyDialLimit) {
        this(campaignType, contactGroupId, didId, contentMode, audioAssetId, ttsTemplateId,
                scheduleStartDate, dailyStartTime, dailyEndTime, timezone,
                allowedDaysOfWeek, holidayCalendarId, retryMaxAttempts, retryIntervalSeconds,
                retryStrategy, null, null, null, typeConfig, callOnWhitelistNumbers, dailyDialLimit);
    }

    /**
     * VB-7C.3: the frozen integration configuration, parsed into the validated
     * typed model.
     *
     * <p>Empty when the campaign configured no integration block — the truthful
     * answer, and deliberately distinct from "configured but disabled". A future
     * delivery or reporting consumer should read its configuration through here
     * and nowhere else; that single choke point is what guarantees such a
     * consumer can never quietly fall back to reading the mutable
     * {@code Campaign} row.
     *
     * @throws IllegalStateException if the stored payload is unreadable, which
     *         would mean the platform cannot interpret its own frozen snapshot
     */
    public java.util.Optional<com.shivang.obd.campaign.config.CampaignIntegrationConfig>
            asIntegrationConfig() {
        if (integrationConfig == null || integrationConfig.isNull()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(
                    com.shivang.obd.campaign.config.CampaignIntegrationConfig
                            .fromJson(integrationConfig));
        } catch (com.shivang.obd.campaign.config.CampaignConfigInvalidException e) {
            throw new IllegalStateException(
                    "Frozen integration configuration is not readable: " + e.getMessage(), e);
        }
    }

    /**
     * Convenience view of the snapshot's schedule window as the same value
     * object the live entity uses, so execution code can consume both
     * uniformly.
     */
    public ScheduleSpec scheduleSpec() {
        if (scheduleStartDate == null
                && dailyStartTime == null && dailyEndTime == null
                && timezone == null && allowedDaysOfWeek == null
                && holidayCalendarId == null) {
            return null;
        }
        return new ScheduleSpec(
                scheduleStartDate,
                dailyStartTime, dailyEndTime,
                timezone, allowedDaysOfWeek, holidayCalendarId);
    }

    /**
     * Convenience view of the snapshot's retry policy as the same value
     * object the live entity uses, so execution code can consume both
     * uniformly.
     */
    public RetryPolicySpec retryPolicySpec() {
        return new RetryPolicySpec(
                retryMaxAttempts, retryIntervalSeconds, retryStrategy, retryRules);
    }
}
