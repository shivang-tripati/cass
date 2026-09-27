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
import com.shivang.obd.voice.routing.VoiceRoutingReason;
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
     * Processes all QUEUED attempts that are due for execution.
     * <p>
     * Safe to call repeatedly — idempotent by design.
     *
     * @return number of attempts processed
     */
    @Transactional
    public int processDueAttempts() {
        List<CallAttempt> dueAttempts = attemptRepository
            .findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(
                CallAttemptStatus.QUEUED, Instant.now());

        if (dueAttempts.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (CallAttempt attempt : dueAttempts) {
            if (processAttempt(attempt)) {
                processed++;
            }
        }

        log.info("Processed {} due call attempts", processed);
        return processed;
    }

    /**
     * Processes a single attempt by invoking the dialer.
     *
     * @return true if attempt was processed, false if skipped (e.g., not QUEUED)
     */
    private boolean processAttempt(CallAttempt attempt) {
        if (attempt.getStatus() != CallAttemptStatus.QUEUED) {
            return false;
        }

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
            // VB-6B.1: the attempt's contact is missing, soft-deleted,
            // foreign, or no longer in the execution's audience group. This
            // is an attempt-specific PERMANENT failure (ContactIdentityService
            // + audit §15): one invalid contact must never abort the due
            // batch, never reach the provider, and never fall back to live
            // campaign configuration.
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

        // Mark IN_PROGRESS before dialing (optimistic — dialer may fail fast)
        attempt.setStatus(CallAttemptStatus.IN_PROGRESS);
        attempt.setStartedAt(Instant.now());
        attemptRepository.save(attempt);

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
                // No route available - check if it's a capacity issue
                String reason = routingDecision.decisionReason();
                log.info("Call attempt {} routing failed: {} - rejected routes: {}", attemptId, reason, routingDecision.rejectedRoutes().size());
                if (VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode().equals(reason)
                        || VoiceRoutingReason.ROUTE_REJECTED_CPS_CAPACITY.getCode().equals(reason)
                        || VoiceRoutingReason.ROUTE_REJECTED_CAPACITY_HEADROOM.getCode().equals(reason)) {
                    // Capacity issue - requeue without consuming retry
                    requeueAttempt(attempt);
                } else {
                    markFailed(attempt, reason, "Routing failed: " + reason);
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
                log.info("Call attempt {} rejected by daily dial limit (contact={}, did={})",
                        attemptId, attempt.getContactId(), actualOutboundDidId);
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