package com.shivang.obd.campaign;

import com.shivang.obd.voice.agent.Agent;
import com.shivang.obd.voice.agent.AgentEligibility;
import com.shivang.obd.voice.agent.AgentLegDialer;
import com.shivang.obd.voice.agent.AgentEndpointEntity;
import com.shivang.obd.voice.agent.AgentReasons;
import com.shivang.obd.voice.agent.AgentReservation;
import com.shivang.obd.voice.agent.AgentReservationService;
import com.shivang.obd.voice.agent.AgentRepository;
import com.shivang.obd.voice.agent.AgentEndpointRepository;
import com.shivang.obd.voice.agent.ReleaseReasons;
import com.shivang.obd.voice.call.CallDirection;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.call.EndpointType;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CONNECT_BY_AGENT execution (VB-3): consumes the VB-2 DTMF action boundary
 * and drives the two-leg connection —
 * <p>
 * DTMF result → agent selection (deterministic) → atomic agent reservation
 * → agent {@link CallLeg} → agent-leg originate → ringing → answer →
 * bridge (CHANNEL_BRIDGE-confirmed) → active conversation → hangup cleanup.
 * <p>
 * Architectural rules enforced here: no raw ESL (media/originate behind
 * boundaries), no second call model (reuses CallSession/CallLeg), no second
 * capacity system (agent reservation is separate from VB-0 voice capacity),
 * tenant isolation on every lookup, idempotent event handling (conditional
 * state transitions + atomic reservation release), one deterministic agent
 * attempt per CONNECT request with a clean failure (no queue invented).
 * <p>
 * Deterministic selection rule (documented contract, see
 * {@link AgentRepository#findEligibleOrdered}): eligible = tenant-scoped,
 * ACTIVE admin status, AVAILABLE runtime availability, enabled dialable
 * endpoint; ordered by least active reservations, then stable agent id —
 * same state always selects the same agent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConnectByAgentService
        implements com.shivang.obd.voice.agent.AgentConnectEvents,
                com.shivang.obd.voice.agent.AgentConnectTrigger {

    /**
     * CONNECT_BY_AGENT entry point (see {@link AgentConnectTrigger}).
     * Idempotent — repeat requests for a session with an existing agent leg
     * return the in-progress connection state.
     */
    @Override
    public com.shivang.obd.voice.agent.AgentEligibility connectByAgent(
            UUID callSessionId, UUID attemptId) {
        return connect(callSessionId, attemptId);
    }

    /** Selection scan width — candidates inspected per connect attempt. */
    private static final int SELECTION_SCAN_LIMIT = 20;

    /** Endpoints dialable by the existing FreeSWITCH setup in VB-3. */
    private static final java.util.Set<EndpointType> DIALABLE_ENDPOINTS =
            java.util.Set.of(EndpointType.SIP, EndpointType.EXTERNAL_FORWARD);

    private final AgentRepository agentRepository;
    private final AgentEndpointRepository endpointRepository;
    private final AgentReservationService reservationService;
    private final CallSessionRepository callSessionRepository;
    private final CallLegRepository callLegRepository;
    private final AgentLegDialer agentLegDialer;
    private final VoiceMediaController mediaController;
    private final com.shivang.obd.did.DidRepository didRepository;

    /**
     * Executes a VALID DTMF result as a CONNECT_BY_AGENT request (VB-3).
     * Idempotent: a session that already has (or had) an agent leg never
     * selects/reserves/originates again — repeat dispatch returns the
     * existing in-progress connection state.
     *
     * @return the selection outcome (selected agent + reason, or rejection
     *         reason) — explainability in the VB-0 routing style
     */
    @Transactional
    public AgentEligibility connect(UUID callSessionId, UUID attemptId) {
        if (callSessionId == null) {
            return AgentEligibility.rejected(AgentReasons.AGENT_CONFIG_INVALID);
        }

        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(callSessionId)
                .orElse(null);
        if (session == null) {
            return AgentEligibility.rejected(AgentReasons.AGENT_CONFIG_INVALID);
        }

        // Idempotent connect: an existing agent leg owns the connection.
        Optional<CallLeg> existing = findAgentLeg(session.getId());
        if (existing.isPresent()) {
            log.debug("CONNECT_BY_AGENT already in progress for session {} — returning existing leg",
                    session.getId());
            return AgentEligibility.selected(
                    agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                            existing.get().getAgentId(), session.getTenantId()).orElse(null),
                    null);
        }

        // Caller must still be on the line.
        if (session.getStatus() != CallSessionStatus.WAITING_FOR_DTMF
                && session.getStatus() != CallSessionStatus.ANSWERED
                && session.getStatus() != CallSessionStatus.CONNECTING_AGENT) {
            log.info("CONNECT_BY_AGENT refused for session {} in state {}",
                    session.getId(), session.getStatus());
            return AgentEligibility.rejected(AgentReasons.AGENT_CONFIG_INVALID);
        }

        UUID tenantId = session.getTenantId();

        // 1. Deterministic eligibility scan (tenant-scoped).
        AgentEligibility selection = selectEligibleAgent(tenantId);
        if (!selection.isSelected()) {
            failSession(session, selection.reasonCode(),
                    "No eligible agent: " + selection.reasonCode());
            return selection;
        }
        Agent agent = selection.candidate();
        AgentEndpointEntity endpoint = selection.endpoint();

        // 2. Atomic reservation (concurrency-safe).
        Optional<AgentReservation> reservation =
                reservationService.reserve(agent.getId(), tenantId, session.getId(), attemptId);
        if (reservation.isEmpty()) {
            log.info("Agent {} reservation lost/limited for session {}", agent.getId(), session.getId());
            failSession(session, AgentReasons.AGENT_BUSY,
                    "Agent " + agent.getId() + " could not be reserved");
            return AgentEligibility.rejected(AgentReasons.AGENT_BUSY);
        }

        // 3. Create the agent leg (explicit role — never positional inference).
        CallLeg agentLeg = new CallLeg();
        agentLeg.setCallSessionId(session.getId());
        agentLeg.setLegType(CallLegType.AGENT);
        agentLeg.setEndpointType(endpoint.getEndpointType());
        agentLeg.setDirection(CallDirection.OUTBOUND);
        agentLeg.setStatus(CallLegStatus.DIALING);
        agentLeg.setTarget(endpoint.getDialTarget());
        agentLeg.setAgentId(agent.getId());
        agentLeg.setInitiatedAt(Instant.now());
        agentLeg = callLegRepository.save(agentLeg);
        reservationService.attachLeg(agentLeg.getId(), session.getId());

        // 4. Session → CONNECTING_AGENT.
        session.setStatus(CallSessionStatus.CONNECTING_AGENT);
        callSessionRepository.save(session);

        // 5. Originate the agent leg. The CLI is the campaign DID (E.164) —
        // the agent should see the same caller id as the customer did.
        try {
            String agentUuid = agentLegDialer.originateAgentLeg(
                    resolveCallerId(session),
                    endpoint.getDialTarget(),
                    null,
                    null);
            agentLeg.setProviderCallId(agentUuid);
            callLegRepository.save(agentLeg);
            log.info("Agent leg originated (callSession={}, agent={}, leg={}, uuid={})",
                    session.getId(), agent.getId(), agentLeg.getId(), maskUuid(agentUuid));
        } catch (RuntimeException e) {
            log.warn("Agent originate failed for session {}: {}",
                    session.getId(), e.getMessage());
            agentLeg.setStatus(CallLegStatus.FAILED);
            agentLeg.setEndedAt(Instant.now());
            agentLeg.setFailureCode("AGENT_ORIGINATE_FAILED");
            agentLeg.setFailureReason(String.valueOf(e.getMessage()));
            callLegRepository.save(agentLeg);
            reservationService.releaseForCallSession(session.getId(), ReleaseReasons.ORIGINATE_FAILED);
            failSession(session, "AGENT_ORIGINATE_FAILED",
                    "Agent leg originate failed: " + e.getMessage());
            return AgentEligibility.rejected(AgentReasons.AGENT_RESERVATION_LOST);
        }

        return AgentEligibility.selected(agent, endpoint);
    }

    // ------------------------------------------------------------------
    // AgentConnectEvents — ESL event callbacks (idempotent)
    // ------------------------------------------------------------------

    @Override
    public void onAgentLegRinging(CallLeg agentLeg) {
        if (agentLeg.getStatus() == CallLegStatus.DIALING) {
            agentLeg.setStatus(CallLegStatus.RINGING);
            callLegRepository.save(agentLeg);
            log.info("Agent leg ringing (callSession={}, leg={})",
                    agentLeg.getCallSessionId(), agentLeg.getId());
        }
        // Duplicate progress after RINGING/ANSWERED: no-op (never regress).
    }

    @Override
    public void onAgentLegAnswered(CallLeg agentLeg) {
        UUID sessionId = agentLeg.getCallSessionId();

        // Idempotency: only the first DIALING/RINGING → ANSWERED transition
        // requests the bridge — a duplicate ANSWER event is a no-op.
        if (agentLeg.getStatus() != CallLegStatus.DIALING
                && agentLeg.getStatus() != CallLegStatus.RINGING) {
            log.debug("Agent leg {} in state {} — duplicate answer ignored",
                    agentLeg.getId(), agentLeg.getStatus());
            return;
        }
        agentLeg.setStatus(CallLegStatus.ANSWERED);
        agentLeg.setAnsweredAt(Instant.now());
        callLegRepository.save(agentLeg);
        log.info("Agent leg answered (callSession={}, leg={})", sessionId, agentLeg.getId());

        // Bridge the caller leg and the agent leg via the media boundary.
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(sessionId)
                .orElse(null);
        Optional<CallLeg> callerLegOpt = findCallerLeg(sessionId);
        if (session == null || callerLegOpt.isEmpty()
                || callerLegOpt.get().getProviderCallId() == null) {
            log.warn("Bridge prerequisites missing for session {} — failing connect", sessionId);
            handleBridgeFailure(session, agentLeg, "caller leg unavailable");
            return;
        }
        CallLeg callerLeg = callerLegOpt.get();

        session.setStatus(CallSessionStatus.CONNECTING_AGENT); // bridging in flight
        callSessionRepository.save(session);
        try {
            mediaController.bridge(session.getId(), callerLeg.getId(), agentLeg.getId());
            log.info("Bridge requested (callSession={}, callerLeg={}, agentLeg={})",
                    sessionId, callerLeg.getId(), agentLeg.getId());
        } catch (RuntimeException e) {
            log.warn("Bridge command failed for session {}: {}", sessionId, e.getMessage());
            handleBridgeFailure(session, agentLeg, e.getMessage());
        }
    }

    @Override
    public void onAgentLegHangup(CallLeg agentLeg, String hangupCause) {
        UUID sessionId = agentLeg.getCallSessionId();
        if (agentLeg.getStatus() == CallLegStatus.COMPLETED
                || agentLeg.getStatus() == CallLegStatus.FAILED
                || agentLeg.getStatus() == CallLegStatus.CANCELLED) {
            log.debug("Agent leg {} already terminal — duplicate hangup ignored", agentLeg.getId());
            return;
        }

        boolean bridged = agentLeg.getStatus() == CallLegStatus.BRIDGED;
        agentLeg.setStatus(bridged ? CallLegStatus.COMPLETED : CallLegStatus.FAILED);
        agentLeg.setEndedAt(Instant.now());
        if (!bridged) {
            agentLeg.setFailureCode("AGENT_ORIGINATE_FAILED".equals(hangupCause)
                    ? "AGENT_ORIGINATE_FAILED" : "AGENT_NO_ANSWER");
            agentLeg.setFailureReason("Agent leg hung up before bridge (cause="
                    + hangupCause + ")");
        }
        callLegRepository.save(agentLeg);

        // Release the agent reservation — always, on every agent hangup path.
        reservationService.releaseForCallSession(sessionId, ReleaseReasons.CALL_ENDED);

        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(sessionId)
                .orElse(null);
        if (session == null) {
            return;
        }

        if (bridged) {
            // Agent hung up mid-conversation: tear down the caller leg so the
            // caller does not remain connected to silence; the caller channel's
            // own CHANNEL_HANGUP finalizes the session/attempt and releases the
            // voice reservation authoritatively (VB-0 rule).
            session.setStatus(CallSessionStatus.FAILED);
            session.setFailureCode("AGENT_LEFT");
            session.setFailureReason("Agent hung up during active conversation");
            session.setEndedAt(Instant.now());
            callSessionRepository.save(session);
            terminateCallerLeg(sessionId, "agent hung up");
        } else {
            // Pre-bridge agent hangup: connect attempt failed; fail the
            // session and tear the caller out. One deterministic attempt —
            // no automatic fallback (documented MVP policy).
            failSession(session, "AGENT_CONNECT_FAILED",
                    "Agent hung up before bridge (cause=" + hangupCause + ")");
            terminateCallerLeg(sessionId, "agent connect failed");
        }
    }

    @Override
    public void onCallerHangup(UUID callSessionId) {
        // Idempotent release; a no-op when no reservation exists.
        reservationService.releaseForCallSession(callSessionId, ReleaseReasons.CALLER_HANGUP);
        // Terminate the agent leg if one exists and is not terminal —
        // best-effort: the media boundary failure must not break cleanup.
        findAgentLeg(callSessionId).ifPresent(agentLeg -> {
            if (agentLeg.getStatus() != CallLegStatus.COMPLETED
                    && agentLeg.getStatus() != CallLegStatus.FAILED
                    && agentLeg.getStatus() != CallLegStatus.CANCELLED
                    && agentLeg.getProviderCallId() != null) {
                try {
                    mediaController.terminateCall(callSessionId, agentLeg.getProviderCallId());
                } catch (RuntimeException e) {
                    log.warn("Agent leg teardown after caller hangup failed for session {}: {}",
                            callSessionId, e.getMessage());
                }
                agentLeg.setStatus(CallLegStatus.CANCELLED);
                agentLeg.setEndedAt(Instant.now());
                callLegRepository.save(agentLeg);
            }
        });
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    @Override
    public void onBridgeConfirmed(UUID callSessionId, String agentUuid) {
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(callSessionId)
                .orElse(null);
        // Idempotency: only the first confirmation performs the
        // CONNECTING_AGENT → BRIDGED transition; duplicate CHANNEL_BRIDGE
        // events (and bridges of non-connecting sessions) are no-ops.
        if (session == null || session.getStatus() != CallSessionStatus.CONNECTING_AGENT) {
            log.debug("Bridge confirmation for session {} in state {} — no-op",
                    callSessionId, session == null ? "missing" : session.getStatus());
            return;
        }
        session.setStatus(CallSessionStatus.BRIDGED);
        callSessionRepository.save(session);

        findCallerLeg(callSessionId)
                .filter(l -> l.getStatus() == CallLegStatus.ANSWERED)
                .ifPresent(l -> {
                    l.setStatus(CallLegStatus.BRIDGED);
                    callLegRepository.save(l);
                });
        findAgentLeg(callSessionId)
                .filter(l -> l.getStatus() == CallLegStatus.ANSWERED)
                .ifPresent(l -> {
                    l.setStatus(CallLegStatus.BRIDGED);
                    callLegRepository.save(l);
                });

        reservationService.markActiveForCallSession(callSessionId);
        log.info("Bridge established (callSession={}, agentChannel={})",
                callSessionId, agentUuid == null ? "unknown" : maskUuid(agentUuid));
    }

    /**
     * Deterministic eligibility scan. Rejects with the most specific reason
     * in priority order (explainability): no agents at all → agents exist
     * but none available → all at capacity. Reservation races surface as
     * AGENT_BUSY from {@link #connect}.
     */
    private AgentEligibility selectEligibleAgent(UUID tenantId) {
        Pageable scan = PageRequest.of(0, SELECTION_SCAN_LIMIT);
        List<Agent> candidates =
                agentRepository.findEligibleOrdered(tenantId, scan).getContent();

        if (candidates.isEmpty()) {
            return AgentEligibility.rejected(noAgentsReason(tenantId));
        }

        for (Agent candidate : candidates) {
            Optional<AgentEndpointEntity> endpoint = endpointRepository
                    .findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                            candidate.getId(), tenantId)
                    .stream()
                    .filter(e -> DIALABLE_ENDPOINTS.contains(e.getEndpointType()))
                    .filter(e -> e.getDialTarget() != null && !e.getDialTarget().isBlank())
                    .findFirst();
            if (endpoint.isPresent()) {
                return AgentEligibility.selected(candidate, endpoint.get());
            }
            log.debug("Agent {} has no dialable endpoint — skipped", candidate.getId());
        }
        return AgentEligibility.rejected(AgentReasons.AGENT_ENDPOINT_INVALID);
    }

    /**
     * Explainable selection scan — public so operations/tests can ask
     * "who would be selected now, and why not" without mutating state
     * (VB-3 explainability, §39).
     */
    public AgentEligibility selectEligibleAgentPublic(UUID tenantId) {
        return selectEligibleAgent(tenantId);
    }

    private String noAgentsReason(UUID tenantId) {
        var anyAgent = agentRepository
                .findByTenantIdAndDeletedAtIsNull(tenantId, PageRequest.of(0, 1));
        return (anyAgent != null && !anyAgent.isEmpty())
                ? AgentReasons.AGENT_NOT_AVAILABLE
                : AgentReasons.AGENT_UNAVAILABLE;
    }

    /** Bridge command failed after the agent answered: clean up the leg. */
    private void handleBridgeFailure(CallSession session, CallLeg agentLeg, String reason) {
        agentLeg.setStatus(CallLegStatus.FAILED);
        agentLeg.setEndedAt(Instant.now());
        agentLeg.setFailureCode("AGENT_BRIDGE_FAILED");
        agentLeg.setFailureReason(reason);
        callLegRepository.save(agentLeg);
        reservationService.releaseForCallSession(session.getId(), ReleaseReasons.BRIDGE_FAILED);
        if (session != null) {
            failSession(session, "AGENT_BRIDGE_FAILED", "Bridge failed: " + reason);
        }
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
        // The caller leg teardown follows the same path as other failures:
        // terminate the caller channel; its CHANNEL_HANGUP finalizes the
        // attempt and releases the voice reservation authoritatively.
        terminateCallerLeg(session.getId(), failureCode);
    }

    private void terminateCallerLeg(UUID sessionId, String why) {
        findCallerLeg(sessionId)
                .filter(l -> l.getProviderCallId() != null && !l.getProviderCallId().isBlank())
                .filter(l -> l.getStatus() != CallLegStatus.COMPLETED
                        && l.getStatus() != CallLegStatus.FAILED
                        && l.getStatus() != CallLegStatus.CANCELLED)
                .ifPresent(callerLeg -> {
                    try {
                        mediaController.terminateCall(sessionId, callerLeg.getProviderCallId());
                    } catch (RuntimeException e) {
                        log.warn("Caller leg teardown failed for session {}: {}",
                                sessionId, e.getMessage());
                    }
                });
    }

    private Optional<CallLeg> findAgentLeg(UUID sessionId) {
        return callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(sessionId, CallLegType.AGENT)
                .stream()
                .findFirst();
    }

    private Optional<CallLeg> findCallerLeg(UUID sessionId) {
        return callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(sessionId, CallLegType.CUSTOMER)
                .stream()
                .findFirst();
    }

    /**
     * Agent legs carry the campaign DID as CLI. Resolved via the session's
     * {@code didId} (null → provider default). Note the caller leg's target
     * is the customer E.164, NOT the CLI — it must never be used as caller
     * id here.
     */
    private String resolveCallerId(CallSession session) {
        return session.getDidId() != null ? didE164(session.getDidId()) : null;
    }

    private String didE164(UUID didId) {
        return didRepository.findByIdAndDeletedAtIsNull(didId)
                .map(com.shivang.obd.did.DidEntity::getE164Number)
                .orElse(null);
    }

    private String maskUuid(String uuid) {
        if (uuid == null || uuid.length() <= 8) {
            return uuid;
        }
        return uuid.substring(0, 8) + "***";
    }
}
