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
import java.time.Instant;
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
     * VB-8B: the one authoritative calling-window calculation. Shared with
     * {@code CallAttemptService} so no attempt-creating path can invent its own
     * scheduling rule.
     */
    private final ExecutionScheduleCalculator scheduleCalculator;

    /**
     * VB-8D: the scheduler's explicit per-item transaction boundary.
     *
     * <p>A template, not an annotation, because the required boundary is per
     * execution <em>inside</em> a loop that must itself hold none. An annotation
     * on the loop's method would put every item in one transaction; an
     * annotation on the item's method would be inert, because the loop calls it
     * on {@code this}. Neither can express "one transaction per item", so the
     * boundary is stated explicitly instead of implied.
     */
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;
    /**
     * VB-6D.2: the single authority for "may this failed attempt be retried,
     * and when?". Injected rather than inlined so this class holds no failure
     * code, category, or allowance knowledge of its own.
     */
    private final RetryPolicyService retryPolicyService;
    private final OutboundDialService dialService;
    private final EslEventProcessor eslEventProcessor;
    /**
     * VB-6E: maximum call duration enforcement and stranded-attempt
     * recovery. Runs on this tick; not a separate scheduler.
     */
    private final StaleCallReconciler staleCallReconciler;

    /**
     * VB-7B: the MISSED_CALL ring-budget sweep, invoked from the existing tick.
     *
     * <p>Injected as an optional setter dependency rather than a constructor
     * argument on purpose: the sweep is a pure addition to the tick, and keeping
     * it off the constructor means no existing construction site — including the
     * two test harnesses that build this orchestrator by hand — changes.
     */
    private org.springframework.beans.factory.ObjectProvider<MissedCallExecutionService>
            missedCallExecution;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMissedCallExecution(
            org.springframework.beans.factory.ObjectProvider<MissedCallExecutionService>
                    missedCallExecution) {
        this.missedCallExecution = missedCallExecution;
    }

    // Terminal attempt statuses
    private static final Set<CallAttemptStatus> TERMINAL_ATTEMPT_STATUSES = Set.of(
        CallAttemptStatus.COMPLETED,
        CallAttemptStatus.FAILED,
        CallAttemptStatus.CANCELLED
    );

    /**
     * Starts a campaign execution if it is in REQUESTED state, on behalf of an
     * interactive caller.
     * <p>
     * Unchanged VB-6E behaviour: the caller's identity comes from the request
     * security context and the {@code CAMPAIGN_EXECUTE} capability is enforced
     * against the execution's tenant. User-triggered execution is therefore
     * exactly as it was.
     * <p>
     * Safe to call repeatedly — idempotent by design.
     *
     * @param executionId the execution to start
     * @return true if execution was started (or already running), false if not eligible
     */
    @Transactional
    public boolean startExecution(UUID executionId) {
        UUID userId = requireUserId();
        CampaignExecution execution = findVisibleExecution(executionId, currentScope());
        authorizationService.requireCapability(userId, CAP_EXECUTE,
            AccessCheck.forTenant(execution.getTenantId()));
        return doStartExecution(execution);
    }

    /**
     * Starts a campaign execution from the scheduler, with no interactive user
     * (VB-6E).
     *
     * <h2>Why this exists</h2>
     *
     * <p>Pre-VB-6E the scheduler's only way to start an execution was
     * {@link #startExecution(UUID)}, whose first statement was
     * {@code requireUserId()}. That reads {@code SecurityContextHolder}, which
     * nothing in the application populates outside a servlet request and which
     * is always empty on a scheduler thread. The result was
     * {@code BusinessException(UNAUTHORIZED)} on <em>every</em> tick, so an
     * execution requested through the REST API never started.
     *
     * <h2>How identity is established instead</h2>
     *
     * <p>Not by fabricating a user. The execution row is the authority: it
     * carries the {@code tenantId} captured when the execution was requested
     * through the authenticated API, and {@code requestedBy} records who asked.
     * So the tenant boundary is known without any security context, and:
     *
     * <ul>
     *   <li>the campaign is re-read with {@code (campaignId, execution.tenantId)},
     *       so a foreign campaign is not found — isolation is enforced;</li>
     *   <li>readiness uses the same rules the interactive path uses
     *       ({@code evaluateForSystem}), so nothing becomes startable that an
     *       interactive start would refuse;</li>
     *   <li>no {@code SecurityContextHolder} is set, so there is no thread-local
     *       authentication to leak to any other work on the scheduler
     *       thread;</li>
     *   <li>{@link #startExecution(UUID)} is untouched, so the interactive path
     *       and its capability check are unchanged.</li>
     * </ul>
     */
    /**
     * VB-8D: this annotation is effective for EXTERNAL callers, which reach
     * this bean through the Spring proxy. The scheduler does NOT rely on it - it
     * used to call this method on {@code this}, which bypassed the proxy and made
     * the annotation inert. The scheduler now establishes its own transaction
     * per execution via the injected template (see scheduledTick), so the
     * annotation and the scheduler no longer have to agree.
     */
    @Transactional
    public boolean startExecutionAsSystem(UUID executionId) {
        CampaignExecution execution = executionRepository
            .findByIdAndDeletedAtIsNull(executionId)
            .orElse(null);
        if (execution == null) {
            log.debug("Scheduled start: execution {} not found", executionId);
            return false;
        }
        return doStartExecution(execution);
    }

    /**
     * The shared, actor-agnostic start logic. Both the interactive and the
     * scheduled entry point converge here, so the two can never diverge in what
     * they consider startable.
     */
    private boolean doStartExecution(CampaignExecution execution) {
        UUID executionId = execution.getId();

        // Only process REQUESTED executions
        if (execution.getStatus() != CampaignExecutionStatus.REQUESTED) {
            log.debug("Execution {} not in REQUESTED state (current: {}), skipping start",
                executionId, execution.getStatus());
            return false;
        }

        UUID tenantId = execution.getTenantId();

        // Reload campaign within the execution's own tenant. Never widened to a
        // caller scope: the execution row is the authority.
        CampaignEntity campaign = campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(
            execution.getCampaignId(), tenantId)
            .orElseThrow(() -> new BusinessException(
                CommonErrorCode.VALIDATION_ERROR, "Campaign no longer exists"));

        CampaignReadinessResponse readiness = readinessService
            .evaluateForSystem(campaign.getId(), tenantId);
        if (!readiness.ready()) {
            // VB-8B: WHY is live campaign state consulted here at all?
            //
            // Readiness is a PRE-EXECUTION gate: it decides whether this
            // execution may start, not what the execution will do. Everything
            // the execution actually does comes from the frozen snapshot
            // (createInitialAttempts resolves CampaignRuntimeConfig below, and
            // the dial path does the same), so this gate cannot change WHAT is
            // executed.
            //
            // It is safe to read the live campaign here because campaign
            // configuration can only be edited in a state that is never
            // executable: a campaign that was edited is necessarily not
            // executable, and a non-executable campaign yields
            // CAMPAIGN_NOT_EXECUTABLE_STATE, which defers this execution rather
            // than acting on it. So a post-snapshot edit can delay an execution
            // but cannot silently reconfigure one.
            //
            // This is an INVARIANT, not an accident - see
            // CampaignLifecyclePolicy.editableAndExecutableAreDisjoint() and
            // CampaignLifecycleInvariantsTest, which exists to make a future
            // change that breaks it loud. Note the limits honestly: the gate can
            // still be moved by live RESOURCE degradation (a deleted asset, a
            // withdrawn DID), which is intentional under the VB-6A
            // snapshot-vs-resource rule, and it does not consult the snapshot's
            // own validity.
            log.warn("Campaign {} not ready: {}", campaign.getId(), formatReasons(readiness.reasons()));
            // VB-6E: a campaign that is merely PAUSED (or SCHEDULED for later)
            // must NOT have its pending execution destroyed. Only a genuinely
            // unrunnable campaign fails here; a non-executable lifecycle state
            // leaves the execution REQUESTED so the scheduler retries it when
            // the campaign becomes executable again.
            if (isDeferredRatherThanFailed(readiness.reasons())) {
                log.info("Execution {} deferred: campaign {} is not executable yet ({})",
                    executionId, campaign.getId(), formatReasons(readiness.reasons()));
                return false;
            }
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
    /**
     * VB-8D: deliberately NOT {@code @Transactional}.
     *
     * <p>This method only SELECTS the running executions; each one is then
     * processed in its own transaction by {@link #processRetriesForExecution}.
     * Making the loop transactional would put every retry attempt in one
     * transaction, so a single poisoned execution would roll back the retries
     * of every other execution - the batch-wide blast radius this phase exists
     * to remove. Selection needs no transaction, so it does not get one.
     */
    public void processRetries() {
        // Find all RUNNING executions
        List<CampaignExecution> runningExecutions = executionRepository
            .findByStatusAndDeletedAtIsNull(CampaignExecutionStatus.RUNNING);

        for (CampaignExecution execution : runningExecutions) {
            // VB-8D: one execution per transaction, and one poisoned execution
            // must not stop the rest. The template gives this loop a real
            // boundary per item, which the previous self-invoked @Transactional
            // never did.
            try {
                txTemplate.executeWithoutResult(
                        status -> processRetriesForExecution(execution));
            } catch (RuntimeException e) {
                log.error("Retry processing failed for execution {}; continuing with the "
                        + "remaining executions of this tick", execution.getId(), e);
            }
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
    /**
     * VB-8D: as with {@link #startExecutionAsSystem}, this annotation applies to
     * external callers reaching the bean through the proxy. The scheduler wraps
     * each call in its own transaction instead of relying on a self-invoked
     * annotation that never fired.
     */
    @Transactional
    public void reconcileExecution(UUID executionId) {
        reconcileExecutionInternal(executionId);
    }

    /**
     * VB-8D: the reconciler's body, callable from inside a transaction the
     * caller established itself.
     *
     * <p>Split out so the scheduler can give each execution its own transaction
     * via the injected template. Calling {@link #reconcileExecution} directly
     * would self-invoke, and that annotation never fired - which is exactly the
     * defect this phase fixes.
     */
    private void reconcileExecutionInternal(UUID executionId) {
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
     *
     * <h2>VB-6E: per-step failure isolation</h2>
     *
     * <p>Pre-VB-6E this method wrapped all five steps in a single
     * {@code try/catch}. Because step 1 could always throw (it required an
     * interactive user), one failure silently disabled retries, dialing, the
     * ESL pump and reconciliation for the whole cycle — every 30 seconds, with
     * nothing but a log line. Each step is now isolated: a step that throws is
     * logged with its identity and the remaining steps still run.
     *
     * <h2>VB-6E: transaction boundary</h2>
     *
     * <p>The method itself is deliberately <b>not</b> {@code @Transactional}, and
     * the tick no longer holds a transaction open across outbound network I/O.
     *
     * <h2>VB-8D: how each step's transaction actually happens</h2>
     *
     * <p>This paragraph previously claimed each step "owns its transaction
     * because they are invoked through the Spring proxy". That was false for
     * this class: the steps call their own methods on {@code this}, so the proxy
     * was bypassed and those annotations never fired. The boundaries are now
     * stated explicitly - the injected {@code txTemplate} opens one transaction
     * per execution inside the two multi-item loops, so a single poisoned item
     * can no longer roll back its neighbours, and so no batch-wide transaction
     * spans the dial step.
     *
     * <p>The cadence (30 s) and the single scheduler are unchanged; this is not
     * a new scheduler.
     */
    @Scheduled(fixedDelay = 30000)
    public void scheduledTick() {
        runStep("start-requested-executions", () -> {
            List<CampaignExecution> requested = executionRepository
                .findByStatusAndDeletedAtIsNull(CampaignExecutionStatus.REQUESTED);
            for (CampaignExecution ex : requested) {
                // VB-8D: a real transaction per execution (the self-invoked
                // @Transactional never fired) plus per-item isolation, so one
                // unrunnable execution cannot stop the others from starting.
                try {
                    txTemplate.executeWithoutResult(status -> doStartExecution(ex));
                } catch (RuntimeException e) {
                    log.error("Starting execution {} failed; continuing with the remaining "
                            + "executions of this tick", ex.getId(), e);
                }
            }
        });

        runStep("process-retries", () -> processRetries());

        runStep("dial-due-attempts", () -> dialService.processDueAttempts());

        runStep("pump-esl-events", () -> eslEventProcessor.ensureEventProcessing());

        runStep("reconcile-executions", () -> {
            List<CampaignExecution> running = executionRepository
                .findByStatusAndDeletedAtIsNull(CampaignExecutionStatus.RUNNING);
            for (CampaignExecution ex : running) {
                // VB-8D: per-execution transaction plus per-item isolation.
                try {
                    txTemplate.executeWithoutResult(
                            status -> reconcileExecutionInternal(ex.getId()));
                } catch (RuntimeException e) {
                    log.error("Reconciling execution {} failed; continuing with the remaining "
                            + "executions of this tick", ex.getId(), e);
                }
            }
        });

        // VB-7E: stale-attempt/stale-session reconciliation runs on the SAME
        // tick, after the other steps, so it never competes with dispatch for
        // the rows it inspects. Not a new scheduler.
        runStep("reconcile-stale-calls", staleCallReconciler::reconcile);

        // VB-7B: MISSED_CALL ring budgets are enforced on the SAME tick, in their
        // own failure boundary. This is deliberately NOT a new @Scheduled
        // component: a MISSED_CALL timeout must not add a scheduler, and running
        // here means it can neither delay nor be delayed by any other step. The
        // optional provider keeps an agent-less / campaign-less deployment (and
        // every existing test harness) constructing exactly as before.
        var missedCall = missedCallExecution != null
                ? missedCallExecution.getIfAvailable() : null;
        if (missedCall != null) {
            runStep("terminate-missed-call-budgets", missedCall::terminateExpired);
        }
    }

    /**
     * Runs one tick step inside its own failure boundary.
     *
     * <p>Failures are logged, never silently swallowed, and never allowed to
     * prevent a later step from running. The scheduler thread always survives.
     */
    private void runStep(String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.error("Scheduled step '{}' failed; continuing with the remaining steps", name, e);
        }
    }

    // === internal ===

    private Instant calculateNextScheduledAt(ScheduleSpec schedule, Instant baseTime) {
        return scheduleCalculator.calculateNextScheduledAt(schedule, baseTime);
    }

    /**
     * VB-6A correction overload: adjusts against the execution's snapshot
     * schedule. Day-of-week interpretation is identical to the plain
     * schedule overload.
     *
     * <p>VB-8B: the calculation itself now lives in
     * {@link ExecutionScheduleCalculator} so the scheduler and the manual
     * attempt path share one implementation. Behaviour is unchanged.
     */
    private Instant adjustToScheduleWindow(
            CampaignRuntimeConfigResolver.CampaignRuntimeConfig config, Instant proposedTime) {
        return scheduleCalculator.adjustToScheduleWindow(config.schedule(), proposedTime);
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

    /**
     * Whether a readiness failure should leave the execution {@code REQUESTED}
     * for a later attempt rather than failing it (VB-6E).
     *
     * <p>Pre-VB-6E, <em>any</em> not-ready result failed the execution
     * immediately. That made a {@code PAUSED} campaign destroy its own pending
     * work, and there is no path from {@code PAUSED} back to {@code REQUESTED},
     * so the execution could never be recovered — pausing was not merely
     * cosmetic, it was destructive.
     *
     * <p>A lifecycle-state reason is transient and time-dependent: the campaign
     * may become executable again on its own (un-paused, or scheduled). A
     * configuration or resource reason is not: a missing audio approval or an
     * invalid DID will still be true on the next tick, and silently retrying it
     * forever would be a hot loop against the database.
     *
     * <p>So the split is by reason kind, not by optimism.
     */
    private boolean isDeferredRatherThanFailed(List<CampaignReadinessReason> reasons) {
        return reasons.stream().anyMatch(r ->
                "CAMPAIGN_NOT_EXECUTABLE_STATE".equals(r.code()));
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
