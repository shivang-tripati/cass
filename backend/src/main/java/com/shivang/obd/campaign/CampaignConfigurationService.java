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
    /**
     * VB-6F: captures a selected IVR tree into the frozen execution config.
     * <p>
     * Optional, so this service still constructs with the two arguments several
     * existing tests use, and so a deployment without IVR support can still
     * create snapshots for every other campaign type. When absent, a campaign
     * whose typeConfig selects an IVR tree fails deterministically rather than
     * silently snapshotting a bare reference that no call could execute.
     */
    private final java.util.Optional<IvrSnapshotCapture> ivrSnapshotCapture;

    public CampaignConfigurationService(
            CampaignExecutionConfigurationRepository snapshotRepository,
            CampaignTypeConfigValidator typeConfigValidator) {
        this(snapshotRepository, typeConfigValidator, java.util.Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CampaignConfigurationService(
            CampaignExecutionConfigurationRepository snapshotRepository,
            CampaignTypeConfigValidator typeConfigValidator,
            java.util.Optional<IvrSnapshotCapture> ivrSnapshotCapture) {
        this.snapshotRepository = snapshotRepository;
        this.typeConfigValidator = typeConfigValidator;
        this.ivrSnapshotCapture = ivrSnapshotCapture;
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

        // VB-6F: an IVR campaign's frozen config carries the whole tree, not just
        // a reference. Captured HERE, at execution-creation time, which is the
        // only point where a snapshot is written — so a live IVR edit can never
        // change a call already running, and a new execution picks the edit up.
        CampaignTypeConfig frozen = freezeIvrIfSelected(campaign, typeConfig);

        CampaignExecutionConfiguration entity = CampaignExecutionConfiguration.materialize(
                campaign.getId(),
                campaign.getTenantId(),
                toSnapshot(campaign, frozen),
                Instant.now());
        return snapshotRepository.save(entity);
    }

    /**
     * VB-6F: replaces an IVR tree <em>reference</em> with the frozen tree.
     *
     * <p>No-op for every other campaign type, and for a DTMF campaign still on
     * the single-level contract. A reference that cannot be captured — an
     * inactive tree, broken structure, unapproved prompt — throws, so an
     * execution is never created that could not be run.
     */
    private CampaignTypeConfig freezeIvrIfSelected(
            CampaignEntity campaign, CampaignTypeConfig typeConfig) {
        if (!(typeConfig instanceof com.shivang.obd.campaign.config.IvrCampaignConfig ivr)) {
            return typeConfig;
        }
        IvrSnapshotCapture capture = ivrSnapshotCapture.orElseThrow(() ->
                new CampaignConfigInvalidException(
                        "This campaign references IVR tree " + ivr.treeId()
                                + " but IVR snapshot capture is unavailable in this deployment."));
        var snapshot = capture.capture(ivr.treeId(), campaign.getTenantId());
        return new com.shivang.obd.campaign.config.IvrCampaignConfig(
                ivr.treeId(), ivr.schemaVersion(), snapshot);
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
                // VB-6D.3: the daily campaign-attempt ceiling is frozen here
                // for the same reason - a later campaign edit must not change
                // a running execution's attempt budget. Null means the
                // platform default.
                campaign.getMaxDailyAttempts(),
                // VB-6E: the maximum call duration is frozen here too. A call
                // already in flight must not be lengthened or shortened by an
                // edit made after the execution was created. Null preserves
                // platform-default semantics.
                campaign.getMaxCallDurationSeconds(),
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
