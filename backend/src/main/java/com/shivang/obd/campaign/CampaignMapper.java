package com.shivang.obd.campaign;

import tools.jackson.databind.JsonNode;
import com.shivang.obd.campaign.dto.CampaignResponse;
import com.shivang.obd.campaign.dto.CreateCampaignRequest;
import com.shivang.obd.campaign.dto.RetryPolicyConfig;
import com.shivang.obd.campaign.dto.RetryRuleConfig;
import com.shivang.obd.campaign.dto.ScheduleConfig;
import com.shivang.obd.campaign.dto.UpdateCampaignRequest;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Single place for DTO &lt;-&gt; entity conversion. Services never map
 * fields inline. Lineage (version/clonedFromCampaignId) is produced only
 * by {@link #cloneOf(CampaignEntity)}.
 */
@Component
public class CampaignMapper {

    public CampaignEntity toEntity(CreateCampaignRequest request, UUID tenantId) {
        CampaignEntity entity = new CampaignEntity();
        entity.setTenantId(tenantId);
        applyCommon(entity, request.name(), request.description(), request.runMode(),
            request.contactGroupId(), request.didId(), request.contentMode(),
            request.audioAssetId(), request.ttsTemplateId(), request.schedule(),
            request.retryPolicy(), request.typeConfig(), request.integrationConfig(),
            request.dailyDialLimit());
        entity.setCampaignType(request.campaignType());
        entity.setCallOnWhitelistNumbers(request.callOnWhitelistNumbers() != null ? request.callOnWhitelistNumbers() : false);
        entity.setDailyDialLimit(request.dailyDialLimit());
        entity.setMaxDailyAttempts(request.maxDailyAttempts());
        return entity;
    }

    public void updateEntity(CampaignEntity entity, UpdateCampaignRequest request) {
        applyCommon(entity, request.name(), request.description(), request.runMode(),
            request.contactGroupId(), request.didId(), request.contentMode(),
            request.audioAssetId(), request.ttsTemplateId(), request.schedule(),
            request.retryPolicy(), request.typeConfig(), request.integrationConfig(),
            request.dailyDialLimit());
    }

    /**
     * Builds a fresh DRAFT campaign from a source: new identity and audit,
     * preserved tenant and configuration, lineage advanced (version+1,
     * clonedFrom = source id). Execution/lifecycle state never carries
     * over.
     */
    public CampaignEntity cloneOf(CampaignEntity source) {
        CampaignEntity clone = new CampaignEntity();
        clone.setTenantId(source.getTenantId());
        clone.setName(source.getName());
        clone.setDescription(source.getDescription());
        clone.setCampaignType(source.getCampaignType());
        clone.setRunMode(source.getRunMode());
        clone.setContactGroupId(source.getContactGroupId());
        clone.setDidId(source.getDidId());
        clone.setContentMode(source.getContentMode());
        clone.setAudioAssetId(source.getAudioAssetId());
        clone.setTtsTemplateId(source.getTtsTemplateId());
        clone.setSchedule(copySchedule(source.getSchedule()));
        RetryPolicySpec sourcePolicy = source.getRetryPolicy();
        clone.setRetryPolicy(sourcePolicy == null
            ? new RetryPolicySpec(0, null, RetryStrategy.FIXED)
            : new RetryPolicySpec(
                sourcePolicy.getMaxAttempts(),
                sourcePolicy.getIntervalSeconds(),
                sourcePolicy.getStrategy()));
        clone.setTypeConfig(source.getTypeConfig());
        clone.setIntegrationConfig(source.getIntegrationConfig());
        clone.setDailyDialLimit(source.getDailyDialLimit());
        clone.setMaxDailyAttempts(source.getMaxDailyAttempts());
        clone.setStatus(CampaignStatus.DRAFT);
        clone.setVersion(source.getVersion() + 1);
        clone.setClonedFromCampaignId(source.getId());
        return clone;
    }

    public CampaignResponse toResponse(CampaignEntity entity) {
        return new CampaignResponse(
            entity.getId(),
            entity.getTenantId(),
            entity.getName(),
            entity.getDescription(),
            entity.getCampaignType(),
            entity.getRunMode(),
            entity.getStatus(),
            entity.getVersion(),
            entity.getClonedFromCampaignId(),
            entity.getContactGroupId(),
            entity.getDidId(),
            entity.getContentMode(),
            entity.getAudioAssetId(),
            entity.getTtsTemplateId(),
            toScheduleView(entity.getSchedule()),
            toRetryView(entity.getRetryPolicy()),
            entity.getTypeConfig(),
            entity.getIntegrationConfig(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getCallOnWhitelistNumbers(),
            entity.getDailyDialLimit(),
            entity.getMaxDailyAttempts()
        );
    }

    // === internal ===

    private void applyCommon(
        CampaignEntity entity,
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
        Integer dailyDialLimit
    ) {        entity.setName(name);
        entity.setDescription(description);
        entity.setRunMode(normalizeRunMode(runMode));
        entity.setContactGroupId(contactGroupId);
        entity.setDidId(didId);
        entity.setContentMode(contentMode);
        entity.setAudioAssetId(audioAssetId);
        entity.setTtsTemplateId(ttsTemplateId);
        entity.setSchedule(toScheduleSpec(schedule));
        entity.setRetryPolicy(normalizeRetry(retryPolicy));
        entity.setTypeConfig(typeConfig);
        entity.setIntegrationConfig(integrationConfig);
        entity.setDailyDialLimit(dailyDialLimit);
    }

    private ScheduleSpec toScheduleSpec(ScheduleConfig view) {
        if (view == null) {
            return null;
        }
        return new ScheduleSpec(
            view.startDate(),
            view.endDate(),
            view.startTime(),
            view.endTime(),
            emptyToNull(view.timezone()),
            normalizeDays(view.allowedDaysOfWeek()),
            view.holidayCalendarId());
    }

    private ScheduleConfig toScheduleView(ScheduleSpec spec) {
        if (spec == null) {
            return null;
        }
        return new ScheduleConfig(
            spec.getStartDate(),
            spec.getEndDate(),
            spec.getStartTime(),
            spec.getEndTime(),
            spec.getTimezone(),
            copyDays(spec.getAllowedDaysOfWeek()),
            spec.getHolidayCalendarId());
    }

    /** Applies the documented defaults so the entity always stores coherent values. */
    private CampaignRunMode normalizeRunMode(CampaignRunMode runMode) {
        return runMode == null ? CampaignRunMode.ONE_TIME : runMode;
    }

    private Set<DayOfWeek> normalizeDays(Set<DayOfWeek> days) {
        return days == null || days.isEmpty() ? null : new LinkedHashSet<>(days);
    }

    private Set<DayOfWeek> copyDays(Set<DayOfWeek> days) {
        return days == null ? null : new LinkedHashSet<>(days);
    }

    private ScheduleSpec copySchedule(ScheduleSpec spec) {
        if (spec == null) {
            return null;
        }
        return new ScheduleSpec(
            spec.getStartDate(),
            spec.getEndDate(),
            spec.getStartTime(),
            spec.getEndTime(),
            spec.getTimezone(),
            copyDays(spec.getAllowedDaysOfWeek()),
            spec.getHolidayCalendarId());
    }

    /**
     * The normalized domain policy for an API view, exposed so
     * {@code CampaignService} can validate exactly what will be persisted
     * (validation must see the post-normalization value, not the raw DTO).
     */
    RetryPolicySpec toDomainRetryPolicy(RetryPolicyConfig view) {
        return normalizeRetry(view);
    }

    /** Applies the documented defaults so the entity always stores a coherent policy. */
    private RetryPolicySpec normalizeRetry(RetryPolicyConfig view) {
        int attempts = view == null || view.maxAttempts() == null ? 0 : view.maxAttempts();
        Integer interval = attempts > 0 && view != null ? view.intervalSeconds() : null;
        RetryStrategy strategy =
            view == null || view.strategy() == null ? RetryStrategy.FIXED : view.strategy();
        // VB-6D.2: per-category rules are stored verbatim. Validation has
        // already run (RetryPolicyValidator), so this only performs the
        // null-to-empty normalization that keeps the column shape stable.
        return new RetryPolicySpec(attempts, interval, strategy, toDomainRules(view));
    }

    /**
     * Converts the API rule view into the persisted domain rules. Returns
     * {@code null} — not an empty list — when no rules are configured, so
     * "no per-category rules" stays distinguishable from "an explicitly empty
     * rule set" in the column.
     */
    private List<RetryRule> toDomainRules(RetryPolicyConfig view) {
        if (view == null || view.rules() == null || view.rules().isEmpty()) {
            return null;
        }
        List<RetryRule> rules = new ArrayList<>(view.rules().size());
        for (RetryRuleConfig rule : view.rules()) {
            rules.add(new RetryRule(
                    rule.category(),
                    rule.enabled() == null ? Boolean.TRUE : rule.enabled(),
                    rule.maxRetries(),
                    // A null delay is legitimate for a rule that permits no
                    // retries (that is how a category is switched off). Parse
                    // only when supplied; RetryPolicyValidator then rejects an
                    // enabled rule that permits retries without one.
                    rule.retryDelay() == null ? null : RetryDelay.parse(rule.retryDelay())));
        }
        return rules;
    }

    private RetryPolicyConfig toRetryView(RetryPolicySpec spec) {
        if (spec == null) {
            return null;
        }
        return new RetryPolicyConfig(spec.getMaxAttempts(), spec.getIntervalSeconds(),
                spec.getStrategy(), toRuleViews(spec.getRules()));
    }

    /** Renders persisted rules back into the API view, or {@code null} when none. */
    private List<RetryRuleConfig> toRuleViews(List<RetryRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        List<RetryRuleConfig> views = new ArrayList<>(rules.size());
        for (RetryRule rule : rules) {
            views.add(new RetryRuleConfig(
                    rule.category(),
                    rule.enabled(),
                    rule.effectiveMaxRetries(),
                    rule.retryDelay() == null ? null : rule.retryDelay().toString()));
        }
        return views;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
