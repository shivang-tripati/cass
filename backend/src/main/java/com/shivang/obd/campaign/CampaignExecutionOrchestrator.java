package com.shivang.obd.campaign;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.campaign.dto.CampaignReadinessReason;
import com.shivang.obd.campaign.dto.CampaignReadinessResponse;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.contact.ContactEntity;
import com.shivang.obd.contact.ContactRepository;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Execution orchestration boundary.
 * <p>
 * Coordinates the lifecycle of a CampaignExecution:
 * - Starting execution (REQUESTED -> RUNNING) and creating initial attempts
 * - Processing retry eligibility for failed attempts
 * - Reconciling execution state when all attempts are terminal
 * <p>
 * This service is intentionally simple — it provides the orchestration
 * primitives that a future scheduler/worker will invoke. It does not
 * perform actual dialing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignExecutionOrchestrator {

    private static final String CAP_EXECUTE = "CAMPAIGN_EXECUTE";

    private final CampaignRepository campaignRepository;
    private final CampaignExecutionRepository executionRepository;
    private final CallAttemptRepository attemptRepository;
    private final ContactRepository contactRepository;
    /** Audience membership bridge (VB-6B.1: contacts participate via memberships). */
    private final com.shivang.obd.contact.ContactGroupMemberRepository memberRepository;
    /** Canonical campaign-resource validation boundary (VB-5E). */
    private final CampaignResourceValidationService resourceValidator;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final CampaignReadinessService readinessService;
    /** Reseller hierarchy resolution for the orchestration boundary (VB-5F). */
    private final TenantRepository tenantRepository;
    /** Execution-scoped configuration resolution (immutable snapshot, VB-6A). */
    private final CampaignRuntimeConfigResolver runtimeConfigResolver;
    /**
     * VB-6D.2: the single authority for "may this failed attempt be retried,
     * and when?". Injected rather than inlined so this class holds no failure
     * code, category, or allowance knowledge of its own.
     */
    private final RetryPolicyService retryPolicyService;
    private final OutboundDialService dialService;
    private final EslEventProcessor eslEventProcessor;

    // Terminal attempt statuses
    private static final Set<CallAttemptStatus> TERMINAL_ATTEMPT_STATUSES = Set.of(
        CallAttemptStatus.COMPLETED,
        CallAttemptStatus.FAILED,
        CallAttemptStatus.CANCELLED
    );

    /**
     * Starts a campaign execution if it is in REQUESTED state.
     * <p>
     * This is the main entry point for the execution engine.
     * Safe to call repeatedly — idempotent by design.
     *
     * @param executionId the execution to start
     * @return true if execution was started (or already running), false if not eligible
     */
    @Transactional
    public boolean startExecution(UUID executionId) {
        UUID userId = requireUserId();
        CampaignExecution execution = findVisibleExecution(executionId, currentScope());
        UUID tenantId = execution.getTenantId();

        authorizationService.requireCapability(userId, CAP_EXECUTE, AccessCheck.forTenant(tenantId));

        // Only process REQUESTED executions
        if (execution.getStatus() != CampaignExecutionStatus.REQUESTED) {
            log.debug("Execution {} not in REQUESTED state (current: {}), skipping start",
                executionId, execution.getStatus());
            return false;
        }

        // Reload campaign and re-check readiness
        CampaignEntity campaign = campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(
            execution.getCampaignId(), tenantId)
            .orElseThrow(() -> new BusinessException(
                CommonErrorCode.VALIDATION_ERROR, "Campaign no longer exists"));

        CampaignReadinessResponse readiness = readinessService.evaluate(campaign.getId());
        if (!readiness.ready()) {
            log.warn("Campaign {} no longer ready: {}", campaign.getId(), formatReasons(readiness.reasons()));
            execution.setStatus(CampaignExecutionStatus.FAILED);
            execution.setCompletedAt(Instant.now());
            execution.setFailureReason("Campaign not ready: " + formatReasons(readiness.reasons()));
            executionRepository.save(execution);
            return false;
        }

        // Transition to RUNNING
        execution.setStatus(CampaignExecutionStatus.RUNNING);
        execution.setStartedAt(Instant.now());
        executionRepository.save(execution);

        // Create initial attempts for all eligible contacts
        createInitialAttempts(execution, campaign);

        log.info("Started execution {} for campaign {}", executionId, campaign.getId());
        return true;
    }

    /**
     * Creates initial call attempts for all live contacts in the campaign's contact group.
     * <p>
     * Uses the existing database uniqueness constraint to prevent duplicate attempts.
     * Safe to call repeatedly.
     */
    private void createInitialAttempts(CampaignExecution execution, CampaignEntity campaign) {
        UUID tenantId = execution.getTenantId();
        // VB-6A correction: initial attempts derive audience/DID/schedule
        // from the execution's immutable snapshot — never from the live
        // campaign, which may be edited independently of this execution.
        var config = runtimeConfigResolver.resolve(execution);
        UUID contactGroupId = config.contactGroupId();
        UUID didId = config.didId();

        if (contactGroupId == null || didId == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                "Campaign missing contact group or DID");
        }

        // Snapshot pins WHAT was requested; DID usability stays dynamic.
        // Validate DID is still usable (canonical resource semantics, VB-5E)
        var didResult = resourceValidator.validateDid(didId, tenantId);
        if (!didResult.usable()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                "Campaign DID no longer available");
        }

        // VB-6B.1: the audience is the group's LIVE MEMBERSHIP — contact
        // identities are resolved through the membership bridge, then kept
        // in memory only for attempt creation. Membership later changes
        // cannot alter this already-started execution (Model A).
        List<UUID> memberContactIds = memberRepository.findByContactGroupId(contactGroupId)
            .stream().map(com.shivang.obd.contact.ContactGroupMemberEntity::getContactId).toList();
        List<ContactEntity> contacts = memberContactIds.isEmpty()
            ? List.of()
            : contactRepository.findAllById(memberContactIds).stream()
                .filter(c -> c.getDeletedAt() == null)
                .toList();

        if (contacts.isEmpty()) {
            log.warn("Campaign {} has no live contacts in group {}", campaign.getId(), contactGroupId);
            return;
        }

        Instant now = Instant.now();
        Instant scheduledAt = calculateNextScheduledAt(config.schedule(), now);

        int created = 0;
        for (ContactEntity contact : contacts) {
            // Check if attempt already exists (idempotency via unique constraint)
            if (attemptRepository.existsByExecutionIdAndContactIdAndAttemptNumberAndDeletedAtIsNull(
                    execution.getId(), contact.getId(), 1)) {
                continue;
            }

            CallAttempt attempt = new CallAttempt();
            attempt.setExecutionId(execution.getId());
            attempt.setCampaignId(campaign.getId());
            attempt.setTenantId(tenantId);
            attempt.setContactId(contact.getId());
            attempt.setDidId(didId);
            attempt.setAttemptNumber(1);
            attempt.setScheduledAt(scheduledAt);
            attempt.setStatus(CallAttemptStatus.QUEUED);

            attemptRepository.save(attempt);
            created++;
        }

        log.info("Created {} initial attempts for execution {}", created, execution.getId());
    }

    /**
     * Processes retry eligibility for failed attempts.
     * <p>
     * For each FAILED attempt in a RUNNING execution, checks if a retry is permitted
     * and creates the next attempt if so.
     * Safe to call repeatedly.
     */
    @Transactional
    public void processRetries() {
        // Find all RUNNING executions
        List<CampaignExecution> runningExecutions = executionRepository
            .findByStatusAndDeletedAtIsNull(CampaignExecutionStatus.RUNNING);

        for (CampaignExecution execution : runningExecutions) {
            processRetriesForExecution(execution);
        }
    }

    private void processRetriesForExecution(CampaignExecution execution) {
        UUID tenantId = execution.getTenantId();
        UUID executionId = execution.getId();

        // Find FAILED attempts for this execution
        List<CallAttempt> failedAttempts = attemptRepository
            .findByExecutionIdAndStatusInAndDeletedAtIsNull(
                executionId, Set.of(CallAttemptStatus.FAILED));

        if (failedAttempts.isEmpty()) {
            return;
        }

        // VB-6A correction: retry policy comes from the execution's immutable
        // snapshot. The live campaign is not consulted — retries always run
        // on the configuration their execution was created with.
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig config =
                runtimeConfigResolver.resolve(execution);

        for (CallAttempt failedAttempt : failedAttempts) {
            // VB-6D.2: the campaign retry policy is the single authority. This
            // method no longer knows any failure code, category, or allowance —
            // it applies a resolved decision. The decision is a pure function
            // (see RetryPolicyService): no counters, no reservations, no I/O.
            RetryDecision decision = retryPolicyService.evaluate(
                    config.retryPolicy(),
                    failedAttempt.getFailureCode(),
                    failedAttempt.getAttemptNumber(),
                    failedAttempt.getCompletedAt());

            if (!decision.retryable()) {
                log.debug("No retry for attempt {} of contact {}: {}",
                    failedAttempt.getAttemptNumber(), failedAttempt.getContactId(),
                    decision.reason());
                continue;
            }

            int nextAttemptNumber = decision.nextAttemptNumber();

            // Check if retry already exists (idempotency). The unique index on
            // (execution_id, contact_id, attempt_number) makes duplicate
            // creation physically impossible; this is the cheap pre-check.
            if (attemptRepository.existsByExecutionIdAndContactIdAndAttemptNumberAndDeletedAtIsNull(
                    executionId, failedAttempt.getContactId(), nextAttemptNumber)) {
                continue;
            }

            // Validate resources still available (against snapshot references)
            if (!isContactStillValid(failedAttempt.getContactId(), tenantId, config.contactGroupId())) {
                log.warn("Contact {} no longer valid for retry", failedAttempt.getContactId());
                continue;
            }

            if (!isDidStillValid(config.didId(), tenantId)) {
                log.warn("DID {} no longer valid for retry", config.didId());
                continue;
            }

            // VB-6D.2: the policy owns the delay arithmetic (timezone
            // independent). Clamping into the snapshot's calling hours stays
            // here because it is a schedule concern, not a policy concern.
            Instant nextScheduledAt = adjustToScheduleWindow(config, decision.nextEligibleAt());

            CallAttempt retryAttempt = new CallAttempt();
            retryAttempt.setExecutionId(executionId);
            retryAttempt.setCampaignId(execution.getCampaignId());
            retryAttempt.setTenantId(tenantId);
            retryAttempt.setContactId(failedAttempt.getContactId());
            retryAttempt.setDidId(config.didId());
            retryAttempt.setAttemptNumber(nextAttemptNumber);
            retryAttempt.setScheduledAt(nextScheduledAt);
            retryAttempt.setStatus(CallAttemptStatus.QUEUED);

            attemptRepository.save(retryAttempt);
            log.info("Created retry attempt {} for execution {}, contact {} ({})",
                nextAttemptNumber, executionId, failedAttempt.getContactId(),
                decision.reason());
        }
    }

    /**
     * Permanent failures can never succeed on retry with the same
     * configuration (VB-1 PLAYBACK_CONFIG_INVALID: missing/unapproved/not-
     * tenant-owned audio asset, and the dial-time hard failures classified by
     * {@code OutboundDialService}).
     *
     * <p>VB-6D.2: this gate no longer lives here. {@link RetryPolicyService}
     * applies the canonical {@link CallFailureCode} permanence check as step 3
     * of its evaluation order, so there is exactly one place that can refuse a
     * retry for a permanent outcome — and the orchestrator cannot drift from
     * it. The helper below was removed rather than left dead: a second copy of
     * the predicate would be a second thing to keep in sync.
     */

    /**
     * Reconciles execution state based on its attempts.
     * <p>
     * Called after attempt status changes to determine if execution is complete.
     * Safe to call repeatedly.
     */
    @Transactional
    public void reconcileExecution(UUID executionId) {
        CampaignExecution execution = findVisibleExecution(executionId, currentScope());

        // Only reconcile RUNNING executions
        if (execution.getStatus() != CampaignExecutionStatus.RUNNING) {
            return;
        }

        UUID tenantId = execution.getTenantId();
        List<CallAttempt> attempts = attemptRepository
            .findByExecutionIdAndTenantIdAndDeletedAtIsNullOrderByScheduledAtAsc(
                executionId, tenantId);

        if (attempts.isEmpty()) {
            // No attempts — execution cannot complete
            return;
        }

        boolean allTerminal = attempts.stream()
            .allMatch(a -> TERMINAL_ATTEMPT_STATUSES.contains(a.getStatus()));

        if (!allTerminal) {
            return; // Still have pending attempts
        }

        boolean anyCompleted = attempts.stream()
            .anyMatch(a -> a.getStatus() == CallAttemptStatus.COMPLETED);

        if (anyCompleted) {
            execution.setStatus(CampaignExecutionStatus.COMPLETED);
        } else {
            execution.setStatus(CampaignExecutionStatus.FAILED);
            execution.setFailureReason("All attempts failed or were cancelled");
        }

        execution.setCompletedAt(Instant.now());
        executionRepository.save(execution);

        log.info("Reconciled execution {} to {}", executionId, execution.getStatus());
    }

    /**
     * Scheduled entry point — processes due attempts and retries.
     * <p>
     * Runs every 30 seconds. The actual interval can be configured via
     * spring.scheduling.cron if needed.
     */
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void scheduledTick() {
        try {
            // 1. Start any REQUESTED executions that are now ready
            List<CampaignExecution> requested = executionRepository
                .findByStatusAndDeletedAtIsNull(CampaignExecutionStatus.REQUESTED);
            for (CampaignExecution ex : requested) {
                startExecution(ex.getId());
            }

            // 2. Process retries for RUNNING executions
            processRetries();

            // 3. Dial due QUEUED attempts
            dialService.processDueAttempts();

            // 4. Ensure ESL event processing is running
            eslEventProcessor.ensureEventProcessing();

            // 5. Reconcile RUNNING executions
            List<CampaignExecution> running = executionRepository
                .findByStatusAndDeletedAtIsNull(CampaignExecutionStatus.RUNNING);
            for (CampaignExecution ex : running) {
                reconcileExecution(ex.getId());
            }
        } catch (Exception e) {
            log.error("Error in scheduled orchestration tick", e);
        }
    }

    // === internal ===

    private Instant calculateNextScheduledAt(ScheduleSpec schedule, Instant baseTime) {
        if (schedule == null || schedule.getTimezone() == null || schedule.getTimezone().isBlank()) {
            return baseTime; // No schedule constraints
        }

        ZoneId zone = ZoneId.of(schedule.getTimezone().trim());
        ZonedDateTime zdt = baseTime.atZone(zone);

        // If schedule window not configured, use base time
        if (!isScheduleWindowConfigured(schedule)) {
            return baseTime;
        }

        // Adjust to next valid time within the execution's schedule window
        return adjustToScheduleWindow(schedule, baseTime);
    }

    /**
     * VB-6A correction overload: adjusts against the execution's snapshot
     * schedule. Day-of-week interpretation is identical to the plain
     * schedule overload.
     */
    private Instant adjustToScheduleWindow(
            CampaignRuntimeConfigResolver.CampaignRuntimeConfig config, Instant proposedTime) {
        return adjustToScheduleWindow(config.schedule(), proposedTime);
    }

    private Instant adjustToScheduleWindow(ScheduleSpec schedule, Instant proposedTime) {
        if (schedule == null) {
            return proposedTime;
        }
        if (schedule == null || schedule.getTimezone() == null || schedule.getTimezone().isBlank()) {
            return proposedTime;
        }

        ZoneId zone = ZoneId.of(schedule.getTimezone().trim());
        ZonedDateTime zdt = proposedTime.atZone(zone);
        ZonedDateTime result = zdt;

        // Ensure date is within [startDate, endDate]
        LocalDate date = result.toLocalDate();
        if (schedule.getStartDate() != null && date.isBefore(schedule.getStartDate())) {
            result = schedule.getStartDate().atTime(result.toLocalTime()).atZone(zone);
        }
        if (schedule.getEndDate() != null && date.isAfter(schedule.getEndDate())) {
            return schedule.getEndDate().plusDays(1).atStartOfDay(zone).toInstant(); // Past end
        }

        // Ensure time is within [startTime, endTime]
        LocalTime time = result.toLocalTime();
        if (schedule.getStartTime() != null && time.isBefore(schedule.getStartTime())) {
            result = result.toLocalDate().atTime(schedule.getStartTime()).atZone(zone);
        }
        if (schedule.getEndTime() != null && time.isAfter(schedule.getEndTime())) {
            // Next day at startTime
            LocalDate nextDate = result.toLocalDate().plusDays(1);
            if (schedule.getStartTime() != null) {
                result = nextDate.atTime(schedule.getStartTime()).atZone(zone);
            } else {
                result = nextDate.atStartOfDay(zone);
            }
        }

        // Ensure day of week is allowed
        Set<DayOfWeek> allowedDays = schedule.getAllowedDaysOfWeek();
        if (allowedDays != null && !allowedDays.isEmpty()) {
            DayOfWeek day = result.getDayOfWeek();
            if (!allowedDays.contains(day)) {
                // Find next allowed day
                int daysToAdd = 1;
                while (daysToAdd <= 7) {
                    DayOfWeek nextDay = day.plus(daysToAdd);
                    if (allowedDays.contains(nextDay)) {
                        result = result.plusDays(daysToAdd);
                        break;
                    }
                    daysToAdd++;
                }
            }
        }

        // Holiday calendar is a reference only — not resolved in this phase
        // ponytail: holidayCalendarId not resolved, add when HolidayCalendar module exists

        // If adjusted time is in the past, return proposed (don't delay further)
        if (result.toInstant().isBefore(proposedTime)) {
            return proposedTime;
        }

        return result.toInstant();
    }

    private boolean isScheduleWindowConfigured(ScheduleSpec schedule) {
        return schedule.getStartDate() != null
            || schedule.getEndDate() != null
            || schedule.getStartTime() != null
            || schedule.getEndTime() != null;
    }

    /**
     * VB-6B.1: retry-time contact validity is identity-scoped — the contact
     * must still be a live row of the execution's tenant. Group membership
     * was decided at attempt-creation time (audience selection); it is not
     * re-verified per retry. DID/compliance re-checks are unchanged.
     */
    private boolean isContactStillValid(UUID contactId, UUID tenantId, UUID contactGroupId) {
        return contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, tenantId)
            .isPresent();
    }

    private boolean isDidStillValid(UUID didId, UUID tenantId) {
        return resourceValidator.validateDid(didId, tenantId).usable();
    }

    private String formatReasons(List<CampaignReadinessReason> reasons) {
        return reasons.stream()
            .map(r -> r.code() + ": " + r.message())
            .reduce((a, b) -> a + "; " + b)
            .orElse("unknown");
    }

    private CampaignExecution findVisibleExecution(UUID executionId, Scope scope) {
        if (scope.tenantId() != null) {
            return executionRepository.findByIdAndTenantIdAndDeletedAtIsNull(executionId, scope.tenantId())
                .orElseThrow(CampaignExecutionOrchestrator::notFound);
        }
        if (scope.resellerId() != null) {
            // Reseller scope is hierarchy-bounded (VB-5F): the previous
            // code passed resellerId as a tenantId, which could never match.
            List<UUID> hierarchyTenants = hierarchyTenantIds(scope.resellerId());
            if (hierarchyTenants.isEmpty()) {
                throw notFound();
            }
            return executionRepository.findByIdAndTenantIdInAndDeletedAtIsNull(executionId, hierarchyTenants)
                .orElseThrow(CampaignExecutionOrchestrator::notFound);
        }
        return executionRepository.findByIdAndDeletedAtIsNull(executionId)
            .orElseThrow(CampaignExecutionOrchestrator::notFound);
    }

    /** Active tenants under a reseller; suspended/soft-deleted tenants are excluded. */
    private List<UUID> hierarchyTenantIds(UUID resellerId) {
        return tenantRepository.findAllByResellerIdAndStatus(resellerId, LifecycleStatus.ACTIVE)
            .stream()
            .map(TenantEntity::getId)
            .toList();
    }

    private record Scope(UUID tenantId, UUID resellerId) {
        static Scope of(com.shivang.obd.authz.context.OrganizationContext ctx) {
            return ctx == null
                ? new Scope(null, null)
                : new Scope(ctx.tenantId(), ctx.resellerId());
        }
    }

    private Scope currentScope() {
        return Scope.of(OrganizationContextHolder.current().orElse(null));
    }

    private UUID requireUserId() {
        return currentUserProvider.current()
            .orElseThrow(() -> new BusinessException(
                CommonErrorCode.UNAUTHORIZED, "Authentication required."))
            .userId();
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Resource not found");
    }
}