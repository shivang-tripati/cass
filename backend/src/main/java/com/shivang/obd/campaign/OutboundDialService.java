package com.shivang.obd.campaign;

import com.shivang.obd.voice.media.GatewayRoute;
import com.shivang.obd.voice.media.OutboundDialException;
import com.shivang.obd.voice.media.OutboundDialRequest;
import com.shivang.obd.voice.media.OutboundDialResponse;
import com.shivang.obd.voice.media.OutboundDialer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shivang.obd.campaign.CallEligibility.EligibilityResult;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.contact.ContactEntity;
import com.shivang.obd.contact.ContactRepository;
import com.shivang.obd.voice.media.PhoneNumberNormalizer;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.call.CallDirection;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.call.CallType;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.routing.RouteType;
import com.shivang.obd.voice.routing.VoiceRoute;
import com.shivang.obd.voice.routing.VoiceRoutingDecision;
import com.shivang.obd.voice.routing.VoiceRoutingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bridges the execution orchestration to the outbound dialer.
 * <p>
 * Takes QUEUED call attempts and hands them to the provider-agnostic
 * {@link OutboundDialer}. Updates attempt state based on dial result.
 * <p>
 * Also creates and links a universal {@link CallSession} and {@link CallLeg}
 * for the voice core, enabling reuse by Contact Center and AI in the future.
 * <p>
 * This is the internal execution boundary — not a public API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundDialService {

    private final CallAttemptRepository attemptRepository;
    private final ContactRepository contactRepository;
    private final TenantRepository tenantRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignExecutionRepository executionRepository;
    /** Execution-scoped configuration resolution (immutable snapshot, VB-6A). */
    private final CampaignRuntimeConfigResolver runtimeConfigResolver;
    private final OutboundDialer dialer;
    private final CallEligibility eligibilityService;
    private final VoiceRoutingService voiceRoutingService;
    private final VoiceCapacityService voiceCapacity;
    private final CallSessionRepository callSessionRepository;
    private final CallLegRepository callLegRepository;
    /**
     * Voice Blast daily dial-limit policy boundary (VB-6C.1): admission
     * after route selection, usage confirmation at provider acceptance
     * (+OK), hold release on every pre-acceptance failure.
     */
    private final DailyDialLimitService dailyDialLimitService;
    /**
     * VB-6D.3: the single authority for campaign daily-attempt safety.
     * Consulted once per dispatch, immediately before the dial is issued.
     */
    private final DailyAttemptSafetyService dailyAttemptSafety;
    /**
     * VB-6E: maps pre-dispatch rejection reasons to canonical codes so they
     * cannot be mistaken for dispatched contact outcomes.
     */
    private final PreDispatchFailureMapper preDispatchFailureMapper;
    /** VB-8J: authoritative calling-window arithmetic for the dispatch gate. */

    /**
     * VB-8D: the explicit transaction boundary for the dial step.
     *
     * <p>Deliberately a template rather than an annotation, because this is the
     * one place where the correct boundary is <em>not</em> the method boundary:
     * the batch loop must run with no transaction at all, while each attempt's
     * work - including the external dial - must run inside exactly one. That
     * cannot be expressed with an annotation on a method owning both.
     */
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;
    /** VB-8J: authoritative calling-window arithmetic for the dispatch gate. */
    private final ExecutionScheduleCalculator scheduleCalculator;

    /**
     * VB-8D: dispatches every due attempt.
     *
     * <h2>Why this method is deliberately NOT {@code @Transactional}</h2>
     *
     * <p>It used to be one transaction spanning the whole platform-wide batch,
     * including {@code dialer.dial(...)} network I/O. That was unsafe in a way
     * no care inside the method could fix: one unexpected {@code RuntimeException}
     * from a <em>later</em> attempt rolled the batch back, erasing the provider
     * call id, session, leg, capacity reservation and safety consumption of calls
     * FreeSWITCH had already accepted. Nothing in the database then referenced
     * those live channels, the ESL hangup could not correlate them, and the
     * attempts became {@code QUEUED} again - so the next tick dialled the same
     * contacts twice.
     *
     * <p>The unit of work is now <b>one attempt</b>, in explicit stages:
     *
     * <ol>
     *   <li><b>claim</b> - a single conditional {@code QUEUED -> IN_PROGRESS}
     *       UPDATE, committed on its own. Once committed the attempt is no longer
     *       selectable, so no later failure anywhere can make it dialable again.
     *       This is what makes duplicate dispatch impossible.</li>
     *   <li><b>dispatch</b> - eligibility, routing, both safety admissions,
     *       capacity reservation and the external dial, in one transaction scoped
     *       to this attempt alone. Another attempt's failure cannot reach it; this
     *       attempt's own failure can only strand itself, which
     *       {@link StaleCallReconciler} already resolves.</li>
     *   <li><b>recover</b> - a failure is turned into a deterministic terminal or
     *       requeued state in its own transaction, so the batch never stops on one
     *       poisoned row.</li>
     * </ol>
     *
     * <p>The ordering is honest about external telephony. The claim commits
     * <em>before</em> the dial, and the attempt's provider evidence is written in
     * the dispatch transaction. Neither direction is pretended to be atomic with
     * PostgreSQL; what is guaranteed is that a dispatch can never be silently
     * forgotten and re-attempted.
     *
     * <p>Safe to call repeatedly - idempotent by design.
     *
     * @return number of attempts processed
     */
    public int processDueAttempts() {
        List<CallAttempt> dueAttempts = attemptRepository
            .findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(
                CallAttemptStatus.QUEUED, Instant.now());

        if (dueAttempts.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (CallAttempt attempt : dueAttempts) {
            // VB-8D: per-ITEM isolation, not merely per-step. One poisoned row
            // must never stop the rest of the batch. RuntimeException only, so a
            // genuine JVM fault still propagates rather than being masked.
            try {
                if (processOneAttempt(attempt)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                log.error("Due attempt {} failed unexpectedly; continuing with the remaining "
                        + "due attempts of this tick", attempt.getId(), e);
                recoverFailedAttempt(attempt.getId(), attempt.getTenantId());
            }
        }

        log.info("Processed {} due call attempts", processed);
        return processed;
    }

    /**
     * VB-8D: claim, then dispatch, as two independently committed units.
     *
     * @return true when this worker both claimed and processed the attempt
     */
    private boolean processOneAttempt(CallAttempt candidate) {
        UUID attemptId = candidate.getId();
        UUID tenantId = candidate.getTenantId();

        // VB-8J: the calling window is checked BEFORE the claim, so a closed
        // window costs one scheduled_at write instead of a claim/requeue cycle
        // per tick per attempt. Nothing is consumed - no VB-6C hold, no VB-6D.3
        // attempt, no capacity - and the attempt stays QUEUED and unfinished,
        // so a window closing never completes an execution.
        if (deferOutsideCallingWindow(candidate)) {
            return false;
        }

        Boolean claimed = txTemplate.execute(status -> attemptRepository.claimForDispatch(
                attemptId, tenantId,
                CallAttemptStatus.QUEUED, CallAttemptStatus.IN_PROGRESS,
                Instant.now()) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            // Another worker won the claim, or the row already moved on. Either
            // way this worker must not touch it.
            log.debug("Attempt {} not claimable (already claimed or no longer due)", attemptId);
            return false;
        }

        return Boolean.TRUE.equals(
                txTemplate.execute(status -> dispatchClaimedAttempt(attemptId, tenantId)));
    }

    /**
     * VB-8J: defers an attempt that is due by timestamp but not inside the
     * execution's frozen calling window.
     *
     * <p>Configuration comes from the execution snapshot, never the live
     * campaign, so a window edited after the freeze cannot change what an
     * existing execution is allowed to dial.
     *
     * @return true when the attempt was deferred and must not be dispatched
     */
    private boolean deferOutsideCallingWindow(CallAttempt attempt) {
        var execution = executionRepository
                .findByIdAndDeletedAtIsNull(attempt.getExecutionId()).orElse(null);
        if (execution == null) {
            return false; // The dispatch path reports the missing execution.
        }
        ScheduleSpec schedule;
        try {
            schedule = runtimeConfigResolver.resolve(execution).schedule();
        } catch (RuntimeException unresolvable) {
            log.error("Execution {} configuration could not be resolved for the calling-window "
                    + "check; leaving the attempt for the dispatch path to report", execution.getId());
            return false;
        }
        Instant now = Instant.now();
        if (scheduleCalculator.isWithinWindow(schedule, now)) {
            return false;
        }
        var nextOpen = scheduleCalculator.nextWindowOpen(schedule, now);
        if (nextOpen.isEmpty()) {
            return false; // No window configured, so it is always open.
        }
        txTemplate.executeWithoutResult(status -> {
            CallAttempt queued = attemptRepository
                    .findByIdAndDeletedAtIsNull(attempt.getId()).orElseThrow();
            if (queued.getStatus() != CallAttemptStatus.QUEUED) {
                return;
            }
            queued.setScheduledAt(nextOpen.get());
            attemptRepository.saveAndFlush(queued);
        });
        log.info("Calling window closed for execution {} - attempt {} deferred to {}",
                execution.getId(), attempt.getId(), nextOpen.get());
        return true;
    }

    /**
     * VB-8D: turns an unexpected failure into a deterministic state, so the
     * attempt can neither be re-dispatched nor disappear without a record.
     *
     * <p>The claim already committed IN_PROGRESS, which is exactly the
     * stranded-dispatch shape StaleCallReconciler handles. Recording it here
     * surfaces the failure on this tick instead of waiting for the sweeper.
     */
    private void recoverFailedAttempt(UUID attemptId, UUID tenantId) {
        try {
            txTemplate.executeWithoutResult(status -> attemptRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(attemptId, tenantId)
                    .ifPresent(attempt -> {
                        if (attempt.getStatus() == CallAttemptStatus.IN_PROGRESS) {
                            // CANCELLED, not FAILED. FAILED is the only status the
                            // retry step looks at, and an internal processing fault
                            // must not silently consume campaign retry budget or
                            // become a retry loop. CANCELLED is already terminal, so
                            // reconcileExecution still settles the execution. The code
                            // is descriptive text rather than a new CallFailureCode
                            // constant, matching how the dial path already persists
                            // eligibility codes - no retry taxonomy is invented here.
                            attempt.setStatus(CallAttemptStatus.CANCELLED);
                            attempt.setFailureCode("ATTEMPT_PROCESSING_FAILED");
                            attempt.setFailureReason(
                                    "Attempt processing failed unexpectedly; see scheduler log");
                            attempt.setCompletedAt(Instant.now());
                            attemptRepository.save(attempt);
                        }
                    }));
        } catch (RuntimeException recoveryFailure) {
            // Nothing further can be done; the attempt stays IN_PROGRESS and
            // StaleCallReconciler remains the backstop.
            log.error("Could not record recovery for attempt {}", attemptId, recoveryFailure);
        }
    }

    /**
     * VB-8D: dispatches one already-claimed attempt, inside its own transaction.
     *
     * <p>Fails closed on a terminal execution before any dispatch work: nothing
     * below runs, so no eligibility check, no routing, no safety admission, no
     * capacity reservation and no telephony side effect occur.
     */
    private boolean dispatchClaimedAttempt(UUID attemptId, UUID tenantId) {
        // Re-read inside the dispatch transaction rather than reusing the row the
        // selection returned: the claim has since changed it, and that committed
        // claim is what proves this worker owns the attempt.
        CallAttempt attempt = attemptRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(attemptId, tenantId)
                .orElse(null);
        if (attempt == null) {
            log.warn("Claimed attempt {} vanished before dispatch", attemptId);
            return false;
        }
        if (attempt.getStatus() != CallAttemptStatus.IN_PROGRESS) {
            log.warn("Claimed attempt {} is no longer IN_PROGRESS (current: {}); skipping",
                    attemptId, attempt.getStatus());
            return false;
        }

        CampaignExecution execution = executionRepository
                .findByIdAndDeletedAtIsNull(attempt.getExecutionId())
                .orElse(null);
        if (execution == null) {
            markFailed(attempt, "EXECUTION_NOT_FOUND", "Execution no longer exists");
            attemptRepository.save(attempt);
            return true;
        }
        if (!execution.getStatus().acceptsDispatchWork()) {
            // CANCELLED, not FAILED: FAILED is the only status the retry step
            // looks at, and a retry for a finished execution is precisely what
            // this guard exists to prevent. CANCELLED is terminal, so
            // reconcileExecution still sees the execution as settled.
            log.info("Attempt {} not dispatched: execution {} is {}",
                    attemptId, execution.getId(), execution.getStatus());
            attempt.setStatus(CallAttemptStatus.CANCELLED);
            attempt.setFailureCode("EXECUTION_NOT_RUNNABLE");
            attempt.setFailureReason("Execution is " + execution.getStatus()
                    + "; no further dispatch is permitted");
            attempt.setCompletedAt(Instant.now());
            attemptRepository.save(attempt);
            return true;
        }

        return dispatch(attempt);
    }

    /**
     * VB-8D: the existing dispatch body, now receiving an already-claimed
     * attempt.
     *
     * <p>Behaviour is unchanged from the previous monolithic processAttempt,
     * except that the QUEUED guard and the IN_PROGRESS write are gone: the
     * atomic claim already proved ownership and durably recorded it before any
     * external I/O.
     */
    private boolean dispatch(CallAttempt attempt) {

        UUID attemptId = attempt.getId();
        log.debug("Processing dial for attempt {}", attemptId);

        // Check call eligibility before dialing. The campaign lookup uses the
        // attempt's denormalized campaign_id (VB-5F): execution_id is a
        // correlation reference to campaign_executions, not the campaign PK.
        // VB-6A correction: eligibility runs against the execution's immutable
        // configuration snapshot — the live campaign is checked only for
        // existence (CAMPAIGN_NOT_FOUND stays a hard failure), never read for
        // execution-affecting configuration.
        CampaignEntity liveCampaign = campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                attempt.getCampaignId(), attempt.getTenantId())
                .orElse(null);
        if (liveCampaign == null) {
            markFailed(attempt, "CAMPAIGN_NOT_FOUND", "Campaign no longer exists");
            attemptRepository.save(attempt);
            return true;
        }

        // VB-6E (PAUSED must actually pause): a paused campaign dispatches
        // nothing new. Checked BEFORE any budget is touched, so pausing never
        // consumes a VB-6C hold or a VB-6D.3 attempt and never produces a
        // failure that could be mistaken for a contact outcome. The attempt
        // stays QUEUED and keeps its attempt number, so it resumes naturally.
        //
        // VB-8H (F-8H-01): the gate was `== PAUSED`, which quietly assumed
        // PAUSED is the only way to stop dispatch. It is not. SCHEDULED ->
        // ARCHIVED is an operator-legal transition on a campaign that can own a
        // RUNNING execution, and ARCHIVED is terminal by definition - yet its
        // attempts kept being dialled, because nothing else consulted the
        // campaign here. Gating on the single existing executable authority
        // (SCHEDULED/RUNNING) instead fixes that and makes the gate fail
        // closed for every non-executable state (PAUSED, ARCHIVED, DRAFT,
        // COMPLETED, FAILED) rather than one hand-picked value. PAUSED
        // behaviour is unchanged: it is not executable, so it is still gated,
        // still before any budget, and still requeued.
        if (!CampaignLifecyclePolicy.isExecutable(liveCampaign.getStatus())) {
            log.info("Campaign {} is {} (not executable) - attempt {} not dispatched",
                    liveCampaign.getId(), liveCampaign.getStatus(), attemptId);
            requeueAttempt(attempt);
            attemptRepository.save(attempt);
            return true;
        }
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig campaign;
        try {
            campaign = resolveExecutionConfig(attempt);
        } catch (ExecutionConfigurationMissingException integrityFailure) {
            log.error("Execution configuration snapshot missing for attempt {} (execution={})",
                    attemptId, attempt.getExecutionId(), integrityFailure);
            markFailed(attempt, "EXECUTION_CONFIG_MISSING",
                    "Execution configuration snapshot missing (data integrity violation)");
            attemptRepository.save(attempt);
            return true;
        }

        // VB-6C.1: the daily-limit usage day is computed in the execution
        // snapshot's IANA timezone — the authoritative zone. A missing or
        // invalid zone fails the dial deterministically (no JVM/UTC
        // fallback) BEFORE any admission or dialing, per the audit's
        // timezone decision.
        LocalDate usageDate;
        try {
            usageDate = dailyDialLimitService.resolveUsageDate(
                    campaign.schedule() != null ? campaign.schedule().getTimezone() : null);
        } catch (ExecutionTimezoneInvalidException invalidTimezone) {
            log.error("Execution timezone invalid for attempt {} (execution={})",
                    attemptId, attempt.getExecutionId(), invalidTimezone);
            markFailed(attempt, CallFailureCode.EXECUTION_TIMEZONE_INVALID.name(),
                    invalidTimezone.getMessage());
            attemptRepository.save(attempt);
            return true;
        }

        String destinationNumber = null;
        try {
            destinationNumber = buildDestinationNumber(attempt);
        } catch (ResourceNotFoundException invalidContact) {
            // VB-6B.1 + VB-8B: the attempt's contact is missing, soft-deleted or
            // foreign, and its phone number cannot be normalized. This is an
            // attempt-specific PERMANENT failure (ContactIdentityService +
            // audit §15): one invalid contact must never abort the due batch,
            // never reach the provider, and never fall back to live campaign
            // configuration.
            //
            // VB-8B correction: this comment used to say "no longer in the
            // execution's audience group". It does not check group membership -
            // buildDestinationNumber has no group predicate by design (see its
            // Javadoc). Audience enforcement happens in CallEligibilityService,
            // against the frozen contact group, and is deliberately skipped for
            // whitelist-enforced campaigns.
            markFailed(attempt, CallFailureCode.CONTACT_INVALID.name(),
                    "Contact is no longer valid for dialing");
            attemptRepository.save(attempt);
            return true;
        }
        EligibilityResult eligibility = eligibilityService.evaluate(
                new CallEligibility.Context(
                        attempt.getTenantId(),
                        null, // reseller resolved inside the voice layer
                        campaign.didId() != null ? campaign.didId() : attempt.getDidId(),
                        campaign.contactGroupId(),
                        Boolean.TRUE.equals(campaign.callOnWhitelistNumbers())),
                destinationNumber);
        if (!eligibility.isAllowed()) {
            log.info("Call attempt {} blocked: {} - {}", attemptId, eligibility.getReasonCode(), eligibility.getReasonMessage());
            // TEMPORARILY_UNAVAILABLE = infrastructure failure, don't consume retry
            if ("TEMPORARILY_UNAVAILABLE".equals(eligibility.getReasonCode())) {
                requeueAttempt(attempt);
            } else {
                markFailed(attempt, eligibility.getReasonCode(), eligibility.getReasonMessage());
            }
            attemptRepository.save(attempt);
            return true;
        }

        // VB-8D: the attempt is ALREADY IN_PROGRESS with startedAt stamped.
        // That transition was made durably by the atomic claim, in its own
        // committed transaction, before any external I/O - which is precisely
        // what stops a later failure from making this attempt dialable again.

        // VB-6C.1: the actual outbound DNID once routing selects the route —
        // declared here so the pre-acceptance failure path (dialer exception)
        // can return the daily-limit hold.
        UUID actualOutboundDidId = null;

        try {
            // Use new VoiceRoutingService for routing decision with primary/overflow/failover
            UUID resellerId = tenantRepository.findByIdAndDeletedAtIsNull(attempt.getTenantId())
                    .map(t -> t.getResellerId())
                    .orElse(null);
            VoiceRoutingDecision routingDecision = voiceRoutingService.resolveRoute(
                    attempt.getTenantId(),
                    resellerId,
                    destinationNumber,
                    campaign.didId() != null ? campaign.didId() : attempt.getDidId(),
                    campaign.campaignType().name(),
                    null // profileId - use tenant default
            );

            if (routingDecision.selectedRoute() == null) {
                // No route available. VB-6E: the reason is translated to a
                // canonical PRE-DISPATCH code before it is persisted. Before
                // this, the raw VoiceRoutingReason name was stored verbatim,
                // canonicalized to HANGUP_UNKNOWN, classified as a HANGUP
                // contact outcome, and therefore consumed campaign retry
                // budget for a call that was never placed.
                String reason = routingDecision.decisionReason();
                log.info("Call attempt {} routing failed: {} - rejected routes: {}", attemptId, reason, routingDecision.rejectedRoutes().size());
                if (preDispatchFailureMapper.isCapacityReason(reason)) {
                    // Capacity issue - requeue without consuming retry
                    requeueAttempt(attempt);
                } else {
                    CallFailureCode preDispatch =
                            preDispatchFailureMapper.toPreDispatchCode(reason);
                    markFailed(attempt, preDispatch.name(),
                            "Routing failed: " + reason);
                }
                attemptRepository.save(attempt);
                return true;
            }

            VoiceRoute selectedRoute = routingDecision.selectedRoute();
            RouteType routeType = routingDecision.routeType();
            log.info("Selected {} route for attempt {}: gateway={}, did={}, reason={}",
                    routeType, attemptId, selectedRoute.freeSwitchGatewayName(), selectedRoute.didE164Number(), routingDecision.decisionReason());

            // VB-6C.1: daily-limit ADMISSION happens after route selection
            // and before capacity reservation. The bucket uses the ACTUAL
            // outbound DNID (routing may substitute a profile-pinned DID for
            // the requested campaign DID — the DNID the provider will see).
            // Rejection consumes nothing: the attempt is failed permanently
            // for today (DAILY_LIMIT_REACHED), never retried same-day.
            actualOutboundDidId = selectedRoute.didId() != null
                    ? selectedRoute.didId()
                    : (campaign.didId() != null ? campaign.didId() : attempt.getDidId());
            // VB-6C.2: the effective limit comes from the execution's
            // immutable snapshot (campaign.dailyDialLimit frozen at snapshot
            // creation; null = platform maximum of 3). The live campaign is
            // never consulted at dial time.
            DailyDialLimitService.AdmissionResult admission = dailyDialLimitService.admit(
                    attempt.getTenantId(), attempt.getContactId(), actualOutboundDidId,
                    usageDate, dailyDialLimitService.effectiveLimit(campaign.dailyDialLimit()));
            if (admission != DailyDialLimitService.AdmissionResult.ADMITTED) {
                // No log here: DailyDialLimitService already logs this exact
                // decision once, at the policy boundary, with the effective
                // limit and bucket identity (VB-6C.3 §6 — no duplicate logs).
                markFailed(attempt, CallFailureCode.DAILY_LIMIT_REACHED.name(),
                        "Voice Blast daily dial limit reached for this contact and DID today");
                attemptRepository.save(attempt);
                return true;
            }

            // Reserve capacity before dialing
            if (!voiceCapacity.reserve(selectedRoute.gatewayId(), attempt.getTenantId())) {
                log.warn("Capacity reservation failed for gateway {} after routing selection", selectedRoute.gatewayId());
                // The dial never reached the provider — return the daily-limit hold.
                dailyDialLimitService.releaseReservation(
                        attempt.getTenantId(), attempt.getContactId(), actualOutboundDidId, usageDate);
                requeueAttempt(attempt);
                attemptRepository.save(attempt);
                return true;
            }

            // Build dial request with selected route
            OutboundDialRequest routedRequest = new OutboundDialRequest(
                    attempt.getId(),
                    selectedRoute.didE164Number(),
                    destinationNumber,
                    attempt.getExecutionId(),
                    attempt.getAttemptNumber(),
                    new GatewayRoute(selectedRoute.gatewayId(), selectedRoute.freeSwitchGatewayName(), selectedRoute.freeSwitchProfile(), selectedRoute.provider())
            );

            // VB-6D.3: campaign daily-attempt safety. Placed deliberately
            // AFTER compliance, routing, the VB-6C DNID limit and capacity,
            // and IMMEDIATELY BEFORE the dial is issued, so the boundary is
            // exactly "a dial is about to be sent":
            //   * a DND / whitelist / contact / routing / VB-6C / capacity
            //     rejection never reaches here, so none consume an attempt (a
            //     capacity requeue keeps the attempt number and clears
            //     startedAt, so re-picking-up the same attempt cannot
            //     double-count either);
            //   * a dial the provider then rejects DOES consume one, which is
            //     the point - a number the provider refuses must not be
            //     dialled forever.
            // Consumption is a single conditional UPDATE in this same
            // transaction: no reservation, so nothing can be stranded.
            DailyAttemptSafetyService.AdmissionResult attemptAdmission =
                    dailyAttemptSafety.admit(attempt.getTenantId(),
                            attempt.getContactId(),
                            campaign.schedule() != null
                                    ? campaign.schedule().getTimezone() : null,
                            campaign.maxDailyAttempts());
            if (attemptAdmission
                    != DailyAttemptSafetyService.AdmissionResult.ADMITTED) {
                // The dial was never issued, so return the VB-6C hold and
                // give the attempt a terminal, non-retryable outcome (the
                // code is PERMANENT, so no same-day retry loop is created).
                dailyDialLimitService.releaseReservation(
                        attempt.getTenantId(), attempt.getContactId(),
                        actualOutboundDidId, usageDate);
                markFailed(attempt,
                        CallFailureCode.DAILY_ATTEMPT_LIMIT_REACHED.name(),
                        "Voice Blast daily campaign attempt limit reached for this contact today");
                attemptRepository.save(attempt);
                return true;
            }

            OutboundDialResponse response = dialer.dial(routedRequest);

            // Create voice core CallSession and CallLeg for this dial attempt
            CallSession callSession = createCallSession(attempt, routedRequest, response, selectedRoute);
            CallLeg callLeg = createCallLeg(callSession, routedRequest, response);

            switch (response.result()) {
                case DIAL_REQUEST_ACCEPTED -> {
                    // Call accepted by provider — remains IN_PROGRESS
                    // Provider will later update to COMPLETED/FAILED via ESL events
                    attempt.setFailureCode(null);
                    attempt.setFailureReason(null);
                    attempt.setProviderCallId(response.providerCallId());
                    // VB-6C.1: the provider ACCEPTED the originate (+OK <uuid>)
                    // — exactly here the dial consumes its daily-limit slot.
                    // Everything after acceptance (ring/no-answer/busy/hangup)
                    // counts; nothing before it did.
                    dailyDialLimitService.confirmAccepted(
                            attempt.getTenantId(), attempt.getId(), attempt.getContactId(),
                            actualOutboundDidId, usageDate, response.providerCallId());
                    log.info("Dial accepted for attempt {} (providerCallId={})", attemptId, response.providerCallId());
                }
                case BUSY -> {
                    markFailed(attempt, "BUSY", response.failureReason());
                    updateCallSessionFailed(callSession, callLeg, "BUSY", response.failureReason());
                    voiceCapacity.release(selectedRoute.gatewayId(), attempt.getTenantId());
                    dailyDialLimitService.releaseReservation(
                            attempt.getTenantId(), attempt.getContactId(), actualOutboundDidId, usageDate);
                }
                case NO_ANSWER -> {
                    markFailed(attempt, "NO_ANSWER", response.failureReason());
                    updateCallSessionFailed(callSession, callLeg, "NO_ANSWER", response.failureReason());
                    voiceCapacity.release(selectedRoute.gatewayId(), attempt.getTenantId());
                    dailyDialLimitService.releaseReservation(
                            attempt.getTenantId(), attempt.getContactId(), actualOutboundDidId, usageDate);
                }
                case REJECTED -> {
                    markFailed(attempt, "REJECTED", response.failureReason());
                    updateCallSessionFailed(callSession, callLeg, "REJECTED", response.failureReason());
                    voiceCapacity.release(selectedRoute.gatewayId(), attempt.getTenantId());
                    dailyDialLimitService.releaseReservation(
                            attempt.getTenantId(), attempt.getContactId(), actualOutboundDidId, usageDate);
                }
                case PROVIDER_UNAVAILABLE -> {
                    // Re-queue for retry — don't consume attempt number
                    requeueAttempt(attempt);
                    updateCallSessionFailed(callSession, callLeg, "PROVIDER_UNAVAILABLE", response.failureReason());
                    voiceCapacity.release(selectedRoute.gatewayId(), attempt.getTenantId());
                    dailyDialLimitService.releaseReservation(
                            attempt.getTenantId(), attempt.getContactId(), actualOutboundDidId, usageDate);
                }
                case FAILED -> {
                    markFailed(attempt, "DIAL_FAILED", response.failureReason());
                    updateCallSessionFailed(callSession, callLeg, "DIAL_FAILED", response.failureReason());
                    voiceCapacity.release(selectedRoute.gatewayId(), attempt.getTenantId());
                    dailyDialLimitService.releaseReservation(
                            attempt.getTenantId(), attempt.getContactId(), actualOutboundDidId, usageDate);
                }
            }

            attemptRepository.save(attempt);
            return true;

        } catch (OutboundDialException e) {
            // Provider fundamentally broken — re-queue. The dial never
            // reached the provider — return the daily-limit hold (when one
            // was granted; admission precedes the dialer call).
            log.error("Outbound dialer unavailable for attempt {}", attemptId, e);
            if (actualOutboundDidId != null) {
                dailyDialLimitService.releaseReservation(
                        attempt.getTenantId(), attempt.getContactId(), actualOutboundDidId, usageDate);
            }
            requeueAttempt(attempt);
            attemptRepository.save(attempt);
            return true;
        }
    }

    /**
     * Creates a CallSession for this dial attempt.
     */
    private CallSession createCallSession(CallAttempt attempt, OutboundDialRequest request, OutboundDialResponse response, VoiceRoute selectedRoute) {
        CallSession session = new CallSession();
        session.setTenantId(attempt.getTenantId());
        // Reseller is looked up from tenant if needed
        session.setDirection(CallDirection.OUTBOUND);
        session.setCallType(CallType.VOICE_BLAST);
        session.setStatus(CallSessionStatus.DIALING);
        session.setDidId(attempt.getDidId());
        session.setDestinationNumber(request.destinationNumber());
        session.setInitiatedAt(Instant.now());
        session.setProviderCallId(response.providerCallId());
        session.setCallAttemptId(attempt.getId());
        session.setCampaignExecutionId(attempt.getExecutionId());
        session.setGatewayId(selectedRoute.gatewayId());
        return callSessionRepository.save(session);
    }

    /**
     * Creates a CallLeg for the customer leg of this call.
     */
    private CallLeg createCallLeg(CallSession session, OutboundDialRequest request, OutboundDialResponse response) {
        CallLeg leg = new CallLeg();
        leg.setCallSessionId(session.getId());
        leg.setLegType(CallLegType.CUSTOMER);
        leg.setDirection(CallDirection.OUTBOUND);
        leg.setStatus(CallLegStatus.DIALING);
        leg.setTarget(request.destinationNumber());
        leg.setProviderCallId(response.providerCallId());
        leg.setInitiatedAt(Instant.now());
        return callLegRepository.save(leg);
    }

    private void updateCallSessionFailed(CallSession session, CallLeg leg, String failureCode, String failureReason) {
        session.setStatus(CallSessionStatus.FAILED);
        session.setEndedAt(Instant.now());
        session.setFailureCode(failureCode);
        session.setFailureReason(failureReason);
        callSessionRepository.save(session);

        leg.setStatus(CallLegStatus.FAILED);
        leg.setEndedAt(Instant.now());
        leg.setFailureCode(failureCode);
        leg.setFailureReason(failureReason);
        callLegRepository.save(leg);
    }

    private void markFailed(CallAttempt attempt, String failureCode, String failureReason) {
        attempt.setStatus(CallAttemptStatus.FAILED);
        attempt.setCompletedAt(Instant.now());
        attempt.setFailureCode(failureCode);
        attempt.setFailureReason(failureReason);
    }

    private void requeueAttempt(CallAttempt attempt) {
        // Re-queue without consuming attempt number — set back to QUEUED
        attempt.setStatus(CallAttemptStatus.QUEUED);
        attempt.setStartedAt(null);
        // scheduledAt could be recalculated here if needed
    }

    /**
     * VB-6A correction: resolves the execution's immutable configuration
     * snapshot. The live campaign row is guaranteed present at this point
     * (CAMPAIGN_NOT_FOUND handled above) but is never read for configuration.
     * A missing execution row is a data-integrity violation and fails
     * deterministically; the attempt is marked FAILED so the dial pipeline
     * keeps processing other attempts (the corruption is logged loudly).
     */
    private CampaignRuntimeConfigResolver.CampaignRuntimeConfig resolveExecutionConfig(
            CallAttempt attempt) {
        CampaignExecution execution = executionRepository
                .findByIdAndDeletedAtIsNull(attempt.getExecutionId())
                .orElseThrow(ExecutionConfigurationMissingException::new);
        return runtimeConfigResolver.resolve(execution);
    }

    /**
     * VB-6B.1: the dial destination is resolved from the contact IDENTITY —
     * tenant-scoped and live. There is deliberately NO group predicate:
     * group membership determined the audience at attempt-creation time and
     * is not a contact-identity constraint. A missing/deleted/foreign
     * contact fails closed (ResourceNotFoundException handled by the caller
     * as a per-attempt permanent CONTACT_INVALID failure); it can never
     * dial another number or crash the batch.
     */
    private String buildDestinationNumber(CallAttempt attempt) {
        ContactEntity contact = contactRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(attempt.getContactId(), attempt.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Contact not found: " + attempt.getContactId()));
        return PhoneNumberNormalizer.normalize(contact.getPhoneNumber());
    }
}
