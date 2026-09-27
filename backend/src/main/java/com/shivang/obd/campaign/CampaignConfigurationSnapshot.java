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
 *       policy, typeConfig, whitelist enforcement flag</li>
 *   <li>Excluded: {@code integrationConfig} — no code reads it today;
 *       snapshotting it would invent execution semantics it does not have</li>
 * </ul>
 * Resource <em>validity</em> of the referenced DID/audio/TTS is never frozen:
 * every runtime check stays dynamic (VB-6A snapshot-vs-resource rule).
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

    @Column(name = "schedule_end_date")
    private LocalDate scheduleEndDate;

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

    // === Type-specific and integration configuration ===

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "type_config")
    private JsonNode typeConfig;

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
            LocalDate scheduleEndDate,
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
                scheduleStartDate, scheduleEndDate, dailyStartTime, dailyEndTime, timezone,
                allowedDaysOfWeek, holidayCalendarId, retryMaxAttempts, retryIntervalSeconds,
                retryStrategy, null, typeConfig, callOnWhitelistNumbers, dailyDialLimit);
    }

    /**
     * Convenience view of the snapshot's schedule window as the same value
     * object the live entity uses, so execution code can consume both
     * uniformly.
     */
    public ScheduleSpec scheduleSpec() {
        if (scheduleStartDate == null && scheduleEndDate == null
                && dailyStartTime == null && dailyEndTime == null
                && timezone == null && allowedDaysOfWeek == null
                && holidayCalendarId == null) {
            return null;
        }
        return new ScheduleSpec(
                scheduleStartDate, scheduleEndDate,
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
