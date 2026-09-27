package com.shivang.obd.campaign;

import com.shivang.obd.campaign.config.CampaignConfigInvalidException;
import com.shivang.obd.campaign.config.CampaignTypeConfig;
import com.shivang.obd.campaign.config.CampaignTypeConfigValidator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the immutable configuration snapshot an execution runs on
 * (VB-6A correction).
 * <p>
 * Called on the execution-creation path: the caller has already validated
 * readiness and authorization; this service validates the typeConfig
 * strictly and persists the immutable snapshot row. Runs in the caller's
 * transaction (REQUIRED) so the snapshot and the execution row commit
 * atomically — an execution can never exist without its snapshot, and no
 * orphan snapshot exists without its execution (§14).
 * <p>
 * The campaign itself is never versioned: there is no per-campaign
 * configuration version sequence, no MAX allocation, and no snapshot
 * reuse. Each execution creates its own snapshot from the campaign's
 * current configuration at creation time.
 */
@Service
public class CampaignConfigurationService {

    private final CampaignExecutionConfigurationRepository snapshotRepository;
    private final CampaignTypeConfigValidator typeConfigValidator;

    public CampaignConfigurationService(
            CampaignExecutionConfigurationRepository snapshotRepository,
            CampaignTypeConfigValidator typeConfigValidator) {
        this.snapshotRepository = snapshotRepository;
        this.typeConfigValidator = typeConfigValidator;
    }

    /**
     * Creates the immutable execution configuration snapshot from the
     * campaign's current configuration. Must be invoked inside the
     * execution-creation transaction, before the execution row is inserted.
     *
     * @return the persisted, immutable snapshot
     * @throws CampaignConfigInvalidException if the campaign's typeConfig is
     *         invalid for its type (the execution request fails
     *         deterministically)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public CampaignExecutionConfiguration createExecutionSnapshot(CampaignEntity campaign) {
        CampaignTypeConfig typeConfig =
                typeConfigValidator.validateAndParse(
                        campaign.getCampaignType(), campaign.getTypeConfig());

        CampaignExecutionConfiguration entity = CampaignExecutionConfiguration.materialize(
                campaign.getId(),
                campaign.getTenantId(),
                toSnapshot(campaign, typeConfig),
                Instant.now());
        return snapshotRepository.save(entity);
    }

    /**
     * Resolves the snapshot an execution must use: mandatory, tenant-scoped.
     * <p>
     * A missing snapshot is an internal data-integrity error — executions
     * are created with their snapshot in the same transaction and the
     * database enforces the association (NOT NULL FK), so an execution
     * without one indicates corruption, not a runtime condition. There is
     * deliberately NO fallback to the live campaign configuration: a
     * running execution must never silently adopt configuration it was not
     * created with.
     *
     * @throws ExecutionConfigurationMissingException if the snapshot does
     *         not exist for the execution's tenant (foreign snapshots are
     *         indistinguishable from missing ones)
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public CampaignExecutionConfiguration requireExecutionSnapshot(
            CampaignExecution execution) {
        return snapshotRepository
                .findByIdAndTenantId(
                        execution.getConfigurationSnapshotId(), execution.getTenantId())
                .orElseThrow(ExecutionConfigurationMissingException::new);
    }

    /** Maps the live campaign's configuration into the snapshot payload. */
    static CampaignConfigurationSnapshot toSnapshot(
            CampaignEntity campaign, CampaignTypeConfig validatedTypeConfig) {
        ScheduleSpec schedule = campaign.getSchedule();
        LocalDateInterval interval = schedule == null
                ? LocalDateInterval.empty()
                : new LocalDateInterval(
                        schedule.getStartDate(), schedule.getEndDate(),
                        schedule.getStartTime(), schedule.getEndTime(),
                        schedule.getTimezone(), schedule.getAllowedDaysOfWeek(),
                        schedule.getHolidayCalendarId());
        RetryPolicySpec retry = campaign.getRetryPolicy() == null
                ? new RetryPolicySpec(0, null, RetryStrategy.FIXED)
                : campaign.getRetryPolicy();

        return new CampaignConfigurationSnapshot(
                campaign.getCampaignType(),
                campaign.getContactGroupId(),
                campaign.getDidId(),
                campaign.getContentMode(),
                campaign.getAudioAssetId(),
                campaign.getTtsTemplateId(),
                interval.startDate(), interval.endDate(),
                interval.startTime(), interval.endTime(),
                interval.timezone(), interval.allowedDaysOfWeek(),
                interval.holidayCalendarId(),
                retry.getMaxAttempts(),
                retry.getIntervalSeconds(),
                retry.getStrategy(),
                // VB-6D.2: per-category retry rules are frozen here, exactly as
                // configured. A campaign edited after this point cannot change
                // this execution's retry behaviour. Null stays null, which
                // means "no per-category rules" (the flat fields govern).
                retry.getRules(),
                // Canonical validated JSON (compatibility codec output).
                validatedTypeConfig.toJson(),
                Boolean.TRUE.equals(campaign.getCallOnWhitelistNumbers()),
                // VB-6C.2: the configured daily dial limit is frozen here.
                // Null is preserved verbatim (platform-default semantics);
                // the runtime computes effectiveLimit(null) = 3.
                campaign.getDailyDialLimit());
    }

    /** Internal carrier for schedule fields (avoids null-spread conditionals). */
    private record LocalDateInterval(
            java.time.LocalDate startDate,
            java.time.LocalDate endDate,
            java.time.LocalTime startTime,
            java.time.LocalTime endTime,
            String timezone,
            java.util.Set<java.time.DayOfWeek> allowedDaysOfWeek,
            UUID holidayCalendarId) {

        static LocalDateInterval empty() {
            return new LocalDateInterval(null, null, null, null, null, null, null);
        }
    }
}
