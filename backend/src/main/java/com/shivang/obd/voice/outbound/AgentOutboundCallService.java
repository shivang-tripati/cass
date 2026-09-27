package com.shivang.obd.voice.outbound;

import com.shivang.obd.voice.media.GatewayRoute;
import com.shivang.obd.voice.media.OutboundDialRequest;
import com.shivang.obd.voice.media.OutboundDialResponse;
import com.shivang.obd.voice.media.OutboundDialResult;
import com.shivang.obd.voice.media.OutboundDialer;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.agent.Agent;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentAvailability;
import com.shivang.obd.voice.agent.AgentConnectEvents;
import com.shivang.obd.voice.agent.AgentEndpointEntity;
import com.shivang.obd.voice.agent.AgentEndpointRepository;
import com.shivang.obd.voice.agent.AgentFoundationReasons;
import com.shivang.obd.voice.agent.AgentLegDialer;
import com.shivang.obd.voice.agent.AgentReasons;
import com.shivang.obd.voice.agent.AgentRepository;
import com.shivang.obd.voice.agent.AgentReservation;
import com.shivang.obd.voice.agent.AgentReservationService;
import com.shivang.obd.voice.agent.ReleaseReasons;
import com.shivang.obd.voice.call.CallDirection;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.call.CallType;
import com.shivang.obd.voice.call.EndpointType;
import com.shivang.obd.voice.media.VoiceMediaController;
import com.shivang.obd.voice.routing.VoiceRoute;
import com.shivang.obd.voice.routing.VoiceRoutingDecision;
import com.shivang.obd.voice.routing.VoiceRoutingService;
import com.shivang.obd.voice.media.PhoneNumberNormalizer;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent-originated outbound call (VB-4E).
 *
 * <p>An authorized agent asks the platform to call one external number
 * now. The call reuses the ONE canonical outbound telephony path —
 * no second dialer, router, capacity system, call model, or reservation
 * system is introduced:</p>
 *
 * <ul>
 *   <li><b>Agent eligibility</b> — same rules as VB-4A availability
 *       (ACTIVE admin status, AVAILABLE presence, dialable endpoint, free
 *       concurrency slot); reasons reuse {@link AgentFoundationReasons}/
 *       {@link AgentReasons}.</li>
 *   <li><b>Routing</b> — the existing {@link VoiceRoutingService}
 *       primary/overflow/failover decision, scoped to the agent's tenant
 *       with the {@code CONTACT_CENTER_OUTBOUND} call type. No
 *       agent-specific gateway selection exists.</li>
 *   <li><b>Capacity</b> — the existing {@link com.shivang.obd.voice.capacity.VoiceCapacityService}
 *       reserve/release (VB-0); capacity is released authoritatively by
 *       the CHANNEL_HANGUP handler for the customer channel, exactly as
 *       for campaign calls.</li>
 *   <li><b>Agent reservation</b> — the existing
 *       {@link AgentReservationService} (PostgreSQL advisory lock);
 *       {@code activeReservations(agent) <= maxConcurrentCalls} holds
 *       under concurrency.</li>
 *   <li><b>Canonical call</b> — one {@link CallSession}
 *       ({@code CONTACT_CENTER_OUTBOUND}) with an AGENT leg (the agent's
 *       endpoint) and a CUSTOMER leg (the originated external channel);
 *       no {@code OutboundAgentCall} aggregate.</li>
 *   <li><b>Telephony</b> — the customer leg originates through the
 *       existing {@link OutboundDialer} boundary (the same
 *       {@code bgapi originate} the campaign path uses). When the
 *       customer answers, the agent leg is originated through the
 *       existing {@link AgentLegDialer} and the bridge follows the
 *       shared VB-3 {@link AgentConnectEvents} boundary (agent answer →
 *       {@code uuid_bridge} → CHANNEL_BRIDGE confirmation), identical to
 *       CONNECT_BY_AGENT and inbound.</li>
 * </ul>
 *
 * <p><b>Sequencing:</b> validate agent → validate destination → route →
 * reserve gateway capacity → create canonical session/legs → reserve
 * agent → originate customer leg. Failures unwind in reverse order and
 * never leave a session pretending an active call exists.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentOutboundCallService {

    /** Endpoints dialable by the existing FreeSWITCH setup (VB-3 rules). */
    private static final Set<EndpointType> DIALABLE_ENDPOINTS =
            Set.of(EndpointType.SIP, EndpointType.EXTERNAL_FORWARD);

    private final AgentRepository agentRepository;
    private final AgentEndpointRepository endpointRepository;
    private final AgentReservationService reservationService;
    private final VoiceRoutingService voiceRoutingService;
    private final com.shivang.obd.voice.capacity.VoiceCapacityService voiceCapacity;
    private final OutboundDialer outboundDialer;
    private final AgentLegDialer agentLegDialer;
    private final VoiceMediaController mediaController;
    private final Optional<AgentConnectEvents> agentConnectEvents;
    private final TenantRepository tenantRepository;
    private final com.shivang.obd.did.DidRepository didRepository;
    private final CallSessionRepository callSessionRepository;
    private final CallLegRepository callLegRepository;

    /**
     * Places an agent-originated outbound call to one external number.
     *
     * @return the accepted result with the canonical call identity
     * @throws AgentOutboundCallException a deterministic, explainable
     *         rejection carrying a machine-readable reason code
     */
    @Transactional
    public AgentOutboundCallResult placeCall(UUID tenantId, UUID agentId, String rawDestination) {

        // 1. Agent validation — tenant-scoped, fail closed.
        Agent agent = agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agentId, tenantId)
                .orElseThrow(() -> new AgentOutboundCallException(
                        AgentReasons.AGENT_CONFIG_INVALID, "Agent not found in tenant"));
        if (agent.getAdminStatus() != AgentAdminStatus.ACTIVE) {
            throw new AgentOutboundCallException(
                    agent.getAdminStatus() == AgentAdminStatus.DISABLED
                            ? AgentFoundationReasons.AGENT_DISABLED
                            : AgentFoundationReasons.AGENT_SUSPENDED,
                    "Agent is not administratively active: " + agent.getAdminStatus());
        }

        // 2. Runtime presence — BUSY is owned by the reservation lifecycle.
        if (agent.getAvailability() != AgentAvailability.AVAILABLE) {
            throw new AgentOutboundCallException(
                    agent.getAvailability() == AgentAvailability.OFFLINE
                            ? AgentFoundationReasons.AGENT_OFFLINE
                            : AgentReasons.AGENT_BUSY,
                    "Agent presence is " + agent.getAvailability());
        }

        // 3. Dialable endpoint (same rules as VB-3/VB-4C/VB-4D).
        AgentEndpointEntity endpoint = dialableEndpoint(agentId, tenantId)
                .orElseThrow(() -> new AgentOutboundCallException(
                        AgentReasons.AGENT_ENDPOINT_INVALID,
                        "Agent has no enabled, dialable endpoint"));

        // 4. Destination validation — existing normalizer, no new abstraction.
        String destination = PhoneNumberNormalizer.normalize(rawDestination);
        if (destination == null || destination.isBlank()
                || !PhoneNumberNormalizer.isValidE164(destination)) {
            throw new AgentOutboundCallException(
                    AgentOutboundReasons.INVALID_DESTINATION,
                    "Destination must be a valid E.164 number");
        }

        // 5. Routing — the existing hierarchy, tenant-scoped. The CLI DID is
        //    resolved from the tenant's assigned pool (deterministic: lowest id);
        //    a profile entry may still pin its own DID via buildVoiceRoute.
        UUID resellerId = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .map(t -> t.getResellerId())
                .orElse(null);
        UUID cliDidId = didRepository
                .findFirstByTenantIdAndDeletedAtIsNullAndStatusAndAllocationStateOrderByIdAsc(
                        tenantId,
                        com.shivang.obd.did.DidStatus.ACTIVE,
                        com.shivang.obd.did.AllocationState.ASSIGNED)
                .map(com.shivang.obd.did.DidEntity::getId)
                .orElseThrow(() -> new AgentOutboundCallException(
                        AgentOutboundReasons.NO_ELIGIBLE_GATEWAY,
                        "No assigned CLI DID available for tenant"));
        VoiceRoutingDecision routing = voiceRoutingService.resolveRoute(
                tenantId, resellerId, destination, cliDidId,
                CallType.CONTACT_CENTER_OUTBOUND.name(), null);
        if (routing.selectedRoute() == null) {
            throw new AgentOutboundCallException(
                    AgentOutboundReasons.NO_ELIGIBLE_GATEWAY,
                    "No eligible outbound route: " + routing.decisionReason());
        }
        VoiceRoute route = routing.selectedRoute();

        // 6. Gateway capacity — existing VB-0 reservation.
        if (!voiceCapacity.reserve(route.gatewayId(), tenantId)) {
            throw new AgentOutboundCallException(
                    AgentOutboundReasons.GATEWAY_CAPACITY_EXHAUSTED,
                    "Gateway capacity reservation failed for gateway " + route.gatewayId());
        }

        // 7. Canonical session + AGENT leg (created before the agent hold so
        //    the reservation is linked to its session for every cleanup path).
        CallSession session = new CallSession();
        session.setTenantId(tenantId);
        session.setResellerId(resellerId);
        session.setDirection(CallDirection.OUTBOUND);
        session.setCallType(CallType.CONTACT_CENTER_OUTBOUND);
        session.setStatus(CallSessionStatus.DIALING);
        session.setDestinationNumber(destination);
        session.setGatewayId(route.gatewayId());
        session.setDidId(route.didId());
        session.setInitiatedAt(Instant.now());
        session = callSessionRepository.save(session);

        CallLeg agentLeg = new CallLeg();
        agentLeg.setCallSessionId(session.getId());
        agentLeg.setLegType(CallLegType.AGENT);
        agentLeg.setEndpointType(endpoint.getEndpointType());
        agentLeg.setDirection(CallDirection.OUTBOUND);
        agentLeg.setStatus(CallLegStatus.INITIATED); // dialed when the customer answers
        agentLeg.setTarget(endpoint.getDialTarget());
        agentLeg.setAgentId(agentId);
        agentLeg.setInitiatedAt(Instant.now());
        agentLeg = callLegRepository.save(agentLeg);

        // 8. Agent reservation — atomic, advisory-lock protected, linked to
        //    the session so every release path (hangup/failure) can find it.
        Optional<AgentReservation> reservation =
                reservationService.reserve(agentId, tenantId, session.getId(), null);
        if (reservation.isEmpty()) {
            failSynchronously(session, null, agentLeg,
                    AgentReasons.AGENT_BUSY,
                    "Agent " + agentId + " could not be reserved");
            voiceCapacity.release(route.gatewayId(), tenantId);
            throw new AgentOutboundCallException(
                    AgentReasons.AGENT_BUSY,
                    "Agent " + agentId + " could not be reserved (capacity/lost race)");
        }
        reservationService.attachLeg(agentLeg.getId(), session.getId());

        // 9. CUSTOMER leg + originate through the existing dialer boundary.
        CallLeg customerLeg = new CallLeg();
        customerLeg.setCallSessionId(session.getId());
        customerLeg.setLegType(CallLegType.CUSTOMER);
        customerLeg.setDirection(CallDirection.OUTBOUND);
        customerLeg.setStatus(CallLegStatus.DIALING);
        customerLeg.setTarget(destination);
        customerLeg.setInitiatedAt(Instant.now());
        customerLeg = callLegRepository.save(customerLeg);

        try {
            OutboundDialResponse response = outboundDialer.dial(new OutboundDialRequest(
                    session.getId(),
                    route.didE164Number(),
                    destination,
                    null,
                    0,
                    new GatewayRoute(
                            route.gatewayId(),
                            route.freeSwitchGatewayName(),
                            route.freeSwitchProfile(),
                            route.provider())));

            if (response.result() == OutboundDialResult.DIAL_REQUEST_ACCEPTED) {
                customerLeg.setProviderCallId(response.providerCallId());
                callLegRepository.save(customerLeg);
                session.setProviderCallId(response.providerCallId());
                callSessionRepository.save(session);
                log.info("Agent outbound call originated (callSession={}, agent={}, "
                                + "customerLeg={}, gateway={})",
                        session.getId(), agentId, customerLeg.getId(), route.gatewayId());
                return AgentOutboundCallResult.accepted(
                        session.getId(), agentLeg.getId(), customerLeg.getId(),
                        agentId, destination);
            }

            // Synchronous provider-side failure — unwind everything.
            String code = mapDialResultToCode(response.result());
            failSynchronously(session, customerLeg, agentLeg, code, response.failureReason());
            voiceCapacity.release(route.gatewayId(), tenantId);
            reservationService.releaseForCallSession(session.getId(), ReleaseReasons.ORIGINATE_FAILED);
            throw new AgentOutboundCallException(
                    AgentOutboundReasons.CALL_ORIGINATE_FAILED,
                    "Customer leg originate failed: " + response.result()
                            + (response.failureReason() != null
                                    ? " (" + response.failureReason() + ")" : ""));

        } catch (AgentOutboundCallException e) {
            throw e;
        } catch (RuntimeException e) {
            // OutboundDialException / ESL failure — same synchronous unwind.
            log.warn("Agent outbound originate failed for session {}: {}",
                    session.getId(), e.getMessage());
            failSynchronously(session, customerLeg, agentLeg,
                    AgentOutboundReasons.CALL_ORIGINATE_FAILED, String.valueOf(e.getMessage()));
            voiceCapacity.release(route.gatewayId(), tenantId);
            reservationService.releaseForCallSession(session.getId(), ReleaseReasons.ORIGINATE_FAILED);
            throw new AgentOutboundCallException(
                    AgentOutboundReasons.CALL_ORIGINATE_FAILED,
                    "Customer leg originate failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Customer-leg events (outbound sessions have no CallAttempt, so the
    // attempt-based ESL path does not apply; the ESL boundary delegates).
    // ------------------------------------------------------------------

    /** Customer-leg progress: DIALING → RINGING (idempotent, never regresses). */
    @Transactional
    public void onCustomerLegRinging(CallLeg customerLeg) {
        if (customerLeg.getStatus() == CallLegStatus.DIALING
                || customerLeg.getStatus() == CallLegStatus.INITIATED) {
            customerLeg.setStatus(CallLegStatus.RINGING);
            callLegRepository.save(customerLeg);
            log.debug("Customer leg ringing (callSession={}, leg={})",
                    customerLeg.getCallSessionId(), customerLeg.getId());
        }
    }

    /**
     * Customer answered (CHANNEL_ANSWER on the CUSTOMER leg). Marks the
     * answer, then originates the agent leg through the existing
     * {@link AgentLegDialer} — the agent device has no channel until now.
     * From here the shared VB-3 boundary owns the rest: agent ringing →
     * agent answer → bridge → CHANNEL_BRIDGE confirmation.
     *
     * <p>Idempotent: duplicate/late ANSWER events never regress state or
     * re-originate.</p>
     */
    @Transactional
    public void onCustomerLegAnswered(CallLeg customerLeg) {
        UUID sessionId = customerLeg.getCallSessionId();
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(sessionId)
                .orElse(null);
        if (session == null
                || session.getCallType() != CallType.CONTACT_CENTER_OUTBOUND) {
            return;
        }

        // Idempotency: only the first pre-answer transition proceeds.
        if (customerLeg.getStatus() != CallLegStatus.DIALING
                && customerLeg.getStatus() != CallLegStatus.RINGING) {
            log.debug("Customer leg {} in state {} — duplicate answer ignored",
                    customerLeg.getId(), customerLeg.getStatus());
            return;
        }
        customerLeg.setStatus(CallLegStatus.ANSWERED);
        customerLeg.setAnsweredAt(Instant.now());
        callLegRepository.save(customerLeg);

        session.setStatus(CallSessionStatus.ANSWERED);
        session.setAnsweredAt(Instant.now());
        callSessionRepository.save(session);
        log.info("Customer answered agent outbound call (callSession={}, leg={})",
                sessionId, customerLeg.getId());

        // An existing agent leg owns the connection (duplicate answer guard).
        Optional<CallLeg> existingAgentLeg = agentLeg(sessionId);
        if (existingAgentLeg.isPresent()
                && existingAgentLeg.get().getProviderCallId() != null) {
            log.debug("Agent leg already originated for session {} — no-op", sessionId);
            return;
        }

        UUID agentId = existingAgentLeg.map(CallLeg::getAgentId).orElse(null);
        if (agentId == null) {
            failCustomerConnected(session, customerLeg, "AGENT_CONFIG_INVALID",
                    "Outbound session has no agent leg");
            return;
        }

        Optional<AgentEndpointEntity> endpoint =
                dialableEndpoint(agentId, session.getTenantId());
        if (endpoint.isEmpty()) {
            failCustomerConnected(session, customerLeg,
                    AgentReasons.AGENT_ENDPOINT_INVALID,
                    "Agent " + agentId + " has no dialable endpoint");
            return;
        }

        CallLeg agentLeg = existingAgentLeg.orElseThrow();
        agentLeg.setStatus(CallLegStatus.DIALING);
        callLegRepository.save(agentLeg);

        session.setStatus(CallSessionStatus.CONNECTING_AGENT);
        callSessionRepository.save(session);

        // Originate the agent leg; CLI is the routing DID (the same number
        // the customer was called from) — consistent with VB-3/VB-4D.
        try {
            String agentUuid = agentLegDialer.originateAgentLeg(
                    session.getDidId() != null ? didE164(session.getDidId()) : null,
                    endpoint.get().getDialTarget(), null, null);
            agentLeg.setProviderCallId(agentUuid);
            callLegRepository.save(agentLeg);
            log.info("Agent leg originated for outbound call (callSession={}, agent={})",
                    sessionId, agentId);
        } catch (RuntimeException e) {
            log.warn("Agent originate failed for outbound session {}: {}",
                    sessionId, e.getMessage());
            agentLeg.setStatus(CallLegStatus.FAILED);
            agentLeg.setEndedAt(Instant.now());
            agentLeg.setFailureCode("AGENT_ORIGINATE_FAILED");
            agentLeg.setFailureReason(String.valueOf(e.getMessage()));
            callLegRepository.save(agentLeg);
            reservationService.releaseForCallSession(sessionId, ReleaseReasons.ORIGINATE_FAILED);
            failSession(session, "AGENT_ORIGINATE_FAILED",
                    "Agent leg originate failed: " + e.getMessage());
            terminateCustomerLeg(session, "agent originate failed");
        }
    }

    /**
     * Customer channel hung up — the authoritative end of an outbound
     * agent call. Finalizes the session/customer leg and delegates the
     * agent-side cleanup (reservation release + agent leg teardown) to
     * the shared, idempotent VB-3 {@code onCallerHangup}.
     */
    @Transactional
    public void onCustomerLegHangup(CallLeg customerLeg, String hangupCause,
                                    UUID tenantId) {
        UUID sessionId = customerLeg.getCallSessionId();
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(sessionId)
                .orElse(null);
        if (session == null
                || session.getCallType() != CallType.CONTACT_CENTER_OUTBOUND) {
            return;
        }

        boolean success = "NORMAL_CLEARING".equals(hangupCause) || hangupCause == null;
        boolean alreadyFinal = session.getStatus() == CallSessionStatus.COMPLETED
                || session.getStatus() == CallSessionStatus.FAILED
                || session.getStatus() == CallSessionStatus.CANCELLED;

        // Customer leg terminal (idempotent — terminal legs untouched).
        if (customerLeg.getStatus() != CallLegStatus.COMPLETED
                && customerLeg.getStatus() != CallLegStatus.FAILED
                && customerLeg.getStatus() != CallLegStatus.CANCELLED) {
            customerLeg.setStatus(success ? CallLegStatus.COMPLETED : CallLegStatus.FAILED);
            customerLeg.setEndedAt(Instant.now());
            if (!success) {
                customerLeg.setFailureCode(mapHangupCause(hangupCause));
                customerLeg.setFailureReason("Hangup cause: " + hangupCause);
            }
            callLegRepository.save(customerLeg);
        }

        // Finalize the session once (duplicate hangups no-op here).
        if (!alreadyFinal) {
            session.setStatus(success ? CallSessionStatus.COMPLETED : CallSessionStatus.FAILED);
            if (!success) {
                session.setFailureCode(mapHangupCause(hangupCause));
                session.setFailureReason("Hangup cause: " + hangupCause);
            }
            session.setEndedAt(Instant.now());
            callSessionRepository.save(session);
        }

        // Shared agent cleanup: release the reservation + terminate the
        // agent leg (no-ops when none/no reservation exists). Skipped on
        // duplicate hangups (already-final session) — matching VB-4D.
        if (!alreadyFinal) {
            agentConnectEvents.ifPresent(events -> events.onCallerHangup(sessionId));
        }

        // VB-0 capacity release — authoritative for outbound sessions
        // (they have no CallAttempt, so the attempt-scoped release in the
        // shared hangup handler does not apply). Skipped on duplicate
        // hangups: the first hangup already released the hold.
        if (!alreadyFinal && session.getGatewayId() != null && tenantId != null) {
            try {
                voiceCapacity.release(session.getGatewayId(), tenantId);
            } catch (RuntimeException e) {
                log.warn("Gateway capacity release failed for session {}: {}",
                        sessionId, e.getMessage());
            }
        }
        log.info("Agent outbound call finalized (callSession={}, cause={}, success={})",
                sessionId, hangupCause, success);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private Optional<CallLeg> agentLeg(UUID sessionId) {
        return callLegRepository
                .findByCallSessionIdAndLegTypeAndDeletedAtIsNull(sessionId, CallLegType.AGENT)
                .stream().findFirst();
    }

    private Optional<AgentEndpointEntity> dialableEndpoint(UUID agentId, UUID tenantId) {
        return endpointRepository
                .findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(agentId, tenantId)
                .stream()
                .filter(e -> DIALABLE_ENDPOINTS.contains(e.getEndpointType()))
                .filter(e -> e.getDialTarget() != null && !e.getDialTarget().isBlank())
                .findFirst();
    }

    private String didE164(UUID didId) {
        return didRepository.findByIdAndDeletedAtIsNull(didId)
                .map(com.shivang.obd.did.DidEntity::getE164Number)
                .orElse(null);
    }

    /** The customer is connected but the agent side failed — fail + tear down. */
    private void failCustomerConnected(CallSession session, CallLeg customerLeg,
                                       String code, String reason) {
        reservationService.releaseForCallSession(session.getId(), code);
        failSession(session, code, reason);
        terminateCustomerLeg(session, reason);
        log.info("Outbound agent connect failed (callSession={}, reason={})",
                session.getId(), reason);
    }

    private void terminateCustomerLeg(CallSession session, String why) {
        Optional<CallLeg> customerLeg = callLegRepository
                .findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                        session.getId(), CallLegType.CUSTOMER)
                .stream().findFirst();
        customerLeg
                .filter(l -> l.getProviderCallId() != null && !l.getProviderCallId().isBlank())
                .filter(l -> l.getStatus() != CallLegStatus.COMPLETED
                        && l.getStatus() != CallLegStatus.FAILED
                        && l.getStatus() != CallLegStatus.CANCELLED)
                .ifPresent(l -> {
                    try {
                        mediaController.terminateCall(session.getId(), l.getProviderCallId());
                    } catch (RuntimeException e) {
                        log.warn("Customer leg teardown failed for session {}: {}",
                                session.getId(), e.getMessage());
                    }
                });
    }

    /** Marks a session FAILED with a code — safe to call on terminal sessions. */
    private void failSession(CallSession session, String failureCode, String failureReason) {
        if (session.getStatus() == CallSessionStatus.COMPLETED
                || session.getStatus() == CallSessionStatus.FAILED
                || session.getStatus() == CallSessionStatus.CANCELLED) {
            return;
        }
        session.setStatus(CallSessionStatus.FAILED);
        session.setFailureCode(failureCode);
        session.setFailureReason(failureReason);
        session.setEndedAt(Instant.now());
        callSessionRepository.save(session);
    }

    private String mapDialResultToCode(OutboundDialResult result) {
        return switch (result) {
            case BUSY -> "BUSY";
            case NO_ANSWER -> "NO_ANSWER";
            case REJECTED -> "REJECTED";
            case PROVIDER_UNAVAILABLE -> "PROVIDER_UNAVAILABLE";
            default -> "DIAL_FAILED";
        };
    }

    /** Same cause mapping as the shared ESL hangup handler (subset used here). */
    private String mapHangupCause(String hangupCause) {
        if (hangupCause == null) {
            return "HANGUP_UNKNOWN";
        }
        return switch (hangupCause) {
            case "17", "USER_BUSY" -> "BUSY";
            case "19", "NO_ANSWER" -> "NO_ANSWER";
            case "16", "NORMAL_CLEARING" -> "COMPLETED";
            case "21", "CALL_REJECTED" -> "REJECTED";
            case "34", "NO_CIRCUIT_AVAILABLE" -> "CONGESTION";
            default -> "HANGUP_" + hangupCause;
        };
    }

    private void failSynchronously(CallSession session, CallLeg customerLeg,
                                   CallLeg agentLeg, String code, String reason) {
        if (customerLeg != null && customerLeg.getStatus() != CallLegStatus.FAILED) {
            customerLeg.setStatus(CallLegStatus.FAILED);
            customerLeg.setEndedAt(Instant.now());
            customerLeg.setFailureCode(code);
            customerLeg.setFailureReason(reason);
            callLegRepository.save(customerLeg);
        }
        if (agentLeg != null && agentLeg.getStatus() != CallLegStatus.FAILED
                && agentLeg.getStatus() != CallLegStatus.CANCELLED) {
            agentLeg.setStatus(CallLegStatus.CANCELLED);
            agentLeg.setEndedAt(Instant.now());
            agentLeg.setFailureCode(code);
            agentLeg.setFailureReason(reason);
            callLegRepository.save(agentLeg);
        }
        failSession(session, code, reason);
    }
}
