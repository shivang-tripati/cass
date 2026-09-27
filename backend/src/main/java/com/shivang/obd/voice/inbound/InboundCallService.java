package com.shivang.obd.voice.inbound;

import com.shivang.obd.voice.acd.AcdService;
import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidInboundDestination;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.voice.agent.Agent;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentConnectEvents;
import com.shivang.obd.voice.agent.AgentEndpointEntity;
import com.shivang.obd.voice.agent.AgentEndpointRepository;
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
import com.shivang.obd.voice.queue.Queue;
import com.shivang.obd.voice.queue.QueueRepository;
import com.shivang.obd.voice.queue.QueueWaitingCall;
import com.shivang.obd.voice.queue.QueueWaitingCallRepository;
import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbound call lifecycle (VB-4D).
 *
 * <p>Inbound and outbound converge on the canonical voice domain: an
 * inbound call IS a {@link CallSession} ({@code CONTACT_CENTER_INBOUND},
 * INBOUND direction) with a CUSTOMER leg (the FreeSWITCH caller channel)
 * and — once an agent is connected — an AGENT leg. No inbound-specific
 * aggregate exists. DID → tenant → destination routing happens here;
 * agent selection/reservation stays with VB-4C ACD; agent-leg telephony
 * stays with the shared VB-3 boundary ({@link AgentConnectEvents}).</p>
 *
 * <p><b>Routing:</b> destination E.164 → live DID → the DID's tenant →
 * configured destination (QUEUE / direct AGENT). A missing/unowned/
 * misconfigured DID fails closed — nothing is created.</p>
 *
 * <p><b>Idempotency:</b> the caller channel UUID is the dedup key —
 * duplicate CHANNEL_CREATE events return the existing session; every
 * subsequent transition is guarded by leg/session state so repeated
 * events never regress or double-act.</p>
 *
 * <p><b>Queue path:</b> session + caller leg + {@code QueueWaitingCall}
 * (WAITING, with the queue's wait budget as {@code expiresAt} snapshot
 * so the VB-4C timeout sweep applies unchanged). Assignment is performed
 * by the existing ACD (retry scheduler); {@link #connectAssignedAgent}
 * then dials the agent through the shared connect boundary, and the
 * CHANNEL_BRIDGE confirmation flows through the existing VB-3 handler.</p>
 *
 * <p><b>Direct-agent path:</b> same canonical session/legs, but the
 * configured agent is reserved and dialed directly (still via the VB-3
 * boundary). No queue row is created.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboundCallService implements InboundCallEvents {

    private final jakarta.persistence.EntityManager entityManager;
    private final DidRepository didRepository;
    private final QueueRepository queueRepository;
    private final QueueWaitingCallRepository waitingCallRepository;
    private final AgentRepository agentRepository;
    private final AgentEndpointRepository endpointRepository;
    private final AgentReservationService reservationService;
    private final AcdService acdService;
    private final CallSessionRepository callSessionRepository;
    private final CallLegRepository callLegRepository;
    private final com.shivang.obd.voice.agent.AgentLegDialer agentLegDialer;
    private final com.shivang.obd.voice.media.VoiceMediaController mediaController;
    private final Optional<AgentConnectEvents> agentConnectEvents;
    /** Advisory-lock base for inbound channel dedup — base 0x4, disjoint from VB-0 (0x1/0x2), VB-3 agent (0x3), and VB-4B membership (0x5) bases. */
    private static final long INBOUND_LOCK_BASE = 0x400000000L;

    // ------------------------------------------------------------------
    // CHANNEL_CREATE — inbound detection + routing
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public Optional<UUID> onInboundChannelCreated(
            String channelUuid, String destinationNumber, String callerNumber) {
        if (channelUuid == null || channelUuid.isBlank()) {
            return Optional.empty();
        }

        // Concurrency boundary (VB-3/VB-4B pattern): a transaction-scoped
        // advisory lock keyed on the channel UUID serializes concurrent
        // CHANNEL_CREATE events for the same channel. Duplicate events
        // BLOCK here, then re-read and observe the winner's committed
        // session — check-then-insert is safe under the lock.
        long lockId = INBOUND_LOCK_BASE + channelUuid.hashCode();
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(CAST(:lockId AS bigint))")
                .setParameter("lockId", lockId)
                .getSingleResult();

        // Idempotency: the caller channel UUID identifies the inbound call.
        Optional<CallSession> existing =
                callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(channelUuid);
        if (existing.isPresent()) {
            log.debug("Inbound CHANNEL_CREATE duplicate for providerCallId={} — "
                    + "existing session {}", channelUuid, existing.get().getId());
            return existing.map(CallSession::getId);
        }

        // 1. DID resolution — destination E.164 must be a live, tenant-owned
        //    DID with an inbound destination configured. Fail closed otherwise.
        DidEntity did = destinationNumber == null || destinationNumber.isBlank()
                ? null
                : didRepository.findByE164NumberAndDeletedAtIsNull(destinationNumber)
                        .orElse(null);
        if (did == null) {
            log.info("Inbound call to unroutable destination (destination={}) — ignoring",
                    destinationNumber);
            return Optional.empty();
        }
        if (did.getTenantId() == null || did.getInboundDestination() == null) {
            log.info("DID {} has no inbound destination — ignoring inbound call "
                    + "(destination={})", did.getId(), destinationNumber);
            return Optional.empty();
        }

        UUID tenantId = did.getTenantId();

        // 2. Canonical CallSession + CUSTOMER leg from the actual channel.
        CallSession session = new CallSession();
        session.setTenantId(tenantId);
        session.setDirection(CallDirection.INBOUND);
        session.setCallType(CallType.CONTACT_CENTER_INBOUND);
        session.setStatus(CallSessionStatus.INITIATED);
        session.setDidId(did.getId());
        session.setDestinationNumber(destinationNumber);
        session.setProviderCallId(channelUuid);
        session.setInitiatedAt(Instant.now());
        session = callSessionRepository.save(session);

        CallLeg callerLeg = new CallLeg();
        callerLeg.setCallSessionId(session.getId());
        callerLeg.setLegType(CallLegType.CUSTOMER);
        callerLeg.setDirection(CallDirection.INBOUND);
        callerLeg.setStatus(CallLegStatus.INITIATED);
        callerLeg.setTarget(callerNumber);
        callerLeg.setProviderCallId(channelUuid);
        callerLeg.setQueueId(did.getInboundDestination() == DidInboundDestination.QUEUE
                ? did.getInboundQueueId() : null);
        callerLeg.setInitiatedAt(Instant.now());
        callLegRepository.save(callerLeg);

        // 3. Destination routing.
        return switch (did.getInboundDestination()) {
            case QUEUE -> routeToQueue(session, tenantId, did);
            case AGENT -> routeToDirectAgent(session, tenantId, did);
        };
    }

    /** Queue destination: enter the VB-4B queue; the ACD retry loop takes over. */
    private Optional<UUID> routeToQueue(CallSession session, UUID tenantId, DidEntity did) {
        UUID queueId = did.getInboundQueueId();
        Queue queue = queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queueId, tenantId)
                .orElse(null);
        if (queue == null) {
            // Fail closed: the configured destination vanished — no telephony
            // state is created beyond the session record.
            log.warn("Inbound DID {} references missing queue {} — failing call",
                    did.getId(), queueId);
            failSession(session, "INBOUND_ROUTE_INVALID",
                    "Configured queue " + queueId + " not found in tenant");
            return Optional.of(session.getId());
        }

        QueueWaitingCall waitingCall = new QueueWaitingCall();
        waitingCall.setTenantId(tenantId);
        waitingCall.setQueueId(queueId);
        waitingCall.setCallSessionId(session.getId());
        waitingCall.setStatus(QueueWaitingCallStatus.WAITING);
        waitingCall.setEnteredAt(Instant.now());
        // VB-4B/VB-4C semantics: expiresAt is the wait-budget snapshot that
        // the existing timeout sweep turns into WAITING→ABANDONED.
        waitingCall.setExpiresAt(Instant.now().plusSeconds(queue.getMaxWaitSeconds()));
        waitingCallRepository.save(waitingCall);

        log.info("Inbound call entered queue (callSession={}, queue={}, did={})",
                session.getId(), queueId, did.getId());
        return Optional.of(session.getId());
    }

    /** Direct-agent destination: eligibility → reservation → agent leg via VB-3 dialing. */
    private Optional<UUID> routeToDirectAgent(CallSession session, UUID tenantId, DidEntity did) {
        UUID agentId = did.getInboundAgentId();
        Agent agent = agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agentId, tenantId)
                .orElse(null);
        if (agent == null || agent.getAdminStatus() != AgentAdminStatus.ACTIVE) {
            log.warn("Inbound DID {} references invalid agent {} — failing call",
                    did.getId(), agentId);
            failSession(session, "INBOUND_ROUTE_INVALID",
                    "Configured agent " + agentId + " not available");
            return Optional.of(session.getId());
        }

        Optional<AgentEndpointEntity> endpoint = dialableEndpoint(agentId, tenantId);
        if (endpoint.isEmpty()) {
            failSession(session, AgentReasons.AGENT_ENDPOINT_INVALID,
                    "Agent " + agentId + " has no dialable endpoint");
            return Optional.of(session.getId());
        }

        // Atomic reservation BEFORE dialing (VB-3 rule).
        Optional<AgentReservation> reservation =
                reservationService.reserve(agentId, tenantId, session.getId(), null);
        if (reservation.isEmpty()) {
            failSession(session, AgentReasons.AGENT_BUSY,
                    "Agent " + agentId + " could not be reserved");
            return Optional.of(session.getId());
        }

        // AGENT leg — same canonical model as outbound CONNECT_BY_AGENT.
        CallLeg agentLeg = newAgentLeg(session.getId(), agentId, endpoint.get());
        agentLeg = callLegRepository.save(agentLeg);
        reservationService.attachLeg(agentLeg.getId(), session.getId());

        session.setStatus(CallSessionStatus.CONNECTING_AGENT);
        callSessionRepository.save(session);

        // Originate the agent leg; the agent sees the inbound DID as CLI.
        try {
            String agentUuid = agentLegDialer.originateAgentLeg(
                    session.getDestinationNumber(), endpoint.get().getDialTarget(), null, null);
            agentLeg.setProviderCallId(agentUuid);
            callLegRepository.save(agentLeg);
            log.info("Inbound agent leg originated (callSession={}, agent={}, leg={})",
                    session.getId(), agentId, agentLeg.getId());
        } catch (RuntimeException e) {
            log.warn("Inbound agent originate failed for session {}: {}",
                    session.getId(), e.getMessage());
            agentLeg.setStatus(CallLegStatus.FAILED);
            agentLeg.setEndedAt(Instant.now());
            agentLeg.setFailureCode("AGENT_ORIGINATE_FAILED");
            agentLeg.setFailureReason(String.valueOf(e.getMessage()));
            callLegRepository.save(agentLeg);
            reservationService.releaseForCallSession(session.getId(),
                    ReleaseReasons.ORIGINATE_FAILED);
            failSession(session, "AGENT_ORIGINATE_FAILED",
                    "Agent leg originate failed: " + e.getMessage());
        }
        return Optional.of(session.getId());
    }

    // ------------------------------------------------------------------
    // Caller-leg events (inbound sessions have no CallAttempt, so the
    // attempt-based event path does not apply to them).
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public void onInboundCallerAnswered(CallLeg callerLeg) {
        UUID sessionId = callerLeg.getCallSessionId();
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(sessionId)
                .orElse(null);
        if (session == null || session.getStatus() != CallSessionStatus.INITIATED) {
            return; // duplicate/late ANSWER: never regress
        }
        session.setStatus(CallSessionStatus.ANSWERED);
        session.setAnsweredAt(Instant.now());
        callSessionRepository.save(session);
        if (callerLeg.getStatus() == CallLegStatus.INITIATED) {
            callerLeg.setStatus(CallLegStatus.ANSWERED);
            callerLeg.setAnsweredAt(Instant.now());
            callLegRepository.save(callerLeg);
        }
        log.info("Inbound caller answered (callSession={})", sessionId);
    }

    @Override
    @Transactional
    public void onInboundCallerHangup(CallLeg callerLeg, String hangupCause) {
        UUID sessionId = callerLeg.getCallSessionId();
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(sessionId)
                .orElse(null);
        if (session == null) {
            return;
        }

        boolean bridged = session.getStatus() == CallSessionStatus.BRIDGED;
        boolean success = bridged
                && ("NORMAL_CLEARING".equals(hangupCause) || hangupCause == null);
        // Pre-existing terminal state → duplicate hangup: finalize/cleanup
        // must not run again.
        boolean alreadyFinal = session.getStatus() == CallSessionStatus.COMPLETED
                || session.getStatus() == CallSessionStatus.FAILED
                || session.getStatus() == CallSessionStatus.CANCELLED;

        // Caller leg terminal (idempotent — terminal legs untouched).
        if (callerLeg.getStatus() != CallLegStatus.COMPLETED
                && callerLeg.getStatus() != CallLegStatus.FAILED
                && callerLeg.getStatus() != CallLegStatus.CANCELLED) {
            callerLeg.setStatus(success ? CallLegStatus.COMPLETED : CallLegStatus.FAILED);
            callerLeg.setEndedAt(Instant.now());
            if (!success) {
                callerLeg.setFailureCode("CALLER_HANGUP");
                callerLeg.setFailureReason("Caller hung up (cause=" + hangupCause + ")");
            }
            callLegRepository.save(callerLeg);
        }

        // Finalize the session once (idempotent — later events no-op).
        if (!alreadyFinal) {
            session.setStatus(success ? CallSessionStatus.COMPLETED : CallSessionStatus.FAILED);
            if (!success) {
                session.setFailureCode("CALLER_HANGUP");
                session.setFailureReason("Caller hangup (cause=" + hangupCause + ")");
            }
            session.setEndedAt(Instant.now());
            callSessionRepository.save(session);
        }

        // Queue entry cleanup: a still-WAITING call is removed (REMOVED).
        // ASSIGNED rows are untouched — their cleanup is owned by the
        // reservation release path below (VB-3 onCallerHangup unwinds them).
        waitingCallRepository.markWaitingRemovedForSession(sessionId);

        // Release the agent reservation and tear down the agent leg — the
        // shared, idempotent VB-3 cleanup (no-op when no reservation/leg).
        // Skipped on duplicate hangups (already-final session).
        if (!alreadyFinal) {
            agentConnectEvents.ifPresent(events -> events.onCallerHangup(sessionId));
        }
        log.info("Inbound caller hangup processed (callSession={}, cause={}, bridged={})",
                sessionId, hangupCause, bridged);
    }

    // ------------------------------------------------------------------
    // ACD queue path — connect an assigned agent
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public boolean connectAssignedAgent(UUID waitingCallId) {
        QueueWaitingCall waitingCall = waitingCallRepository
                .findByIdAndDeletedAtIsNull(waitingCallId).orElse(null);
        if (waitingCall == null
                || waitingCall.getStatus() != QueueWaitingCallStatus.ASSIGNED
                || waitingCall.getAssignedAgentId() == null) {
            return false; // not connectable (released/timeout/unknown)
        }
        UUID sessionId = waitingCall.getCallSessionId();

        // Idempotency: an existing agent leg owns the connection.
        boolean alreadyConnecting = callLegRepository
                .findByCallSessionIdAndLegTypeAndDeletedAtIsNull(sessionId, CallLegType.AGENT)
                .stream().findFirst().isPresent();
        if (alreadyConnecting) {
            return true;
        }

        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(sessionId)
                .orElse(null);
        if (session == null) {
            return false;
        }

        UUID tenantId = session.getTenantId();
        UUID agentId = waitingCall.getAssignedAgentId();
        Optional<AgentEndpointEntity> endpoint = dialableEndpoint(agentId, tenantId);
        if (endpoint.isEmpty()) {
            // Agent became undialable after assignment: unwind the assignment
            // (existing ACD release → back to WAITING for the next attempt).
            log.warn("Assigned agent {} has no dialable endpoint (waitingCall={}) "
                    + "— releasing assignment", agentId, waitingCallId);
            acdService.releaseAssignment(waitingCallId, AgentReasons.AGENT_ENDPOINT_INVALID);
            return false;
        }

        // Stamp the reservation's session linkage (ACD reserved against the
        // canonical session id already) and create the AGENT leg.
        CallLeg agentLeg = newAgentLeg(sessionId, agentId, endpoint.get());
        agentLeg = callLegRepository.save(agentLeg);
        reservationService.attachLeg(agentLeg.getId(), sessionId);

        session.setStatus(CallSessionStatus.CONNECTING_AGENT);
        callSessionRepository.save(session);

        // Originate; CLI is the inbound DID so the agent sees the caller's
        // dialed number — same convention as the outbound connect flow.
        try {
            String agentUuid = agentLegDialer.originateAgentLeg(
                    session.getDestinationNumber(), endpoint.get().getDialTarget(), null, null);
            agentLeg.setProviderCallId(agentUuid);
            callLegRepository.save(agentLeg);
            log.info("Inbound agent leg originated after ACD assignment "
                    + "(callSession={}, agent={}, waitingCall={})",
                    sessionId, agentId, waitingCallId);
        } catch (RuntimeException e) {
            log.warn("Inbound agent originate failed for session {}: {}",
                    sessionId, e.getMessage());
            agentLeg.setStatus(CallLegStatus.FAILED);
            agentLeg.setEndedAt(Instant.now());
            agentLeg.setFailureCode("AGENT_ORIGINATE_FAILED");
            agentLeg.setFailureReason(String.valueOf(e.getMessage()));
            callLegRepository.save(agentLeg);
            // Release the assignment (reservation + WAITING unwind) so the
            // next ACD attempt can pick another agent — no orphan hold.
            acdService.releaseAssignment(waitingCallId, ReleaseReasons.ORIGINATE_FAILED);
            // Caller stays ANSWERED in the queue — the retry loop re-assigns.
            session.setStatus(CallSessionStatus.ANSWERED);
            callSessionRepository.save(session);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /** First dialable endpoint of the agent (same rules as VB-3/VB-4C). */
    private Optional<AgentEndpointEntity> dialableEndpoint(UUID agentId, UUID tenantId) {
        return endpointRepository
                .findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(agentId, tenantId)
                .stream()
                .filter(e -> e.getEndpointType() == EndpointType.SIP
                        || e.getEndpointType() == EndpointType.EXTERNAL_FORWARD)
                .filter(e -> e.getDialTarget() != null && !e.getDialTarget().isBlank())
                .findFirst();
    }

    /** Canonical AGENT leg in DIALING state (no provider UUID yet). */
    private CallLeg newAgentLeg(UUID sessionId, UUID agentId, AgentEndpointEntity endpoint) {
        CallLeg agentLeg = new CallLeg();
        agentLeg.setCallSessionId(sessionId);
        agentLeg.setLegType(CallLegType.AGENT);
        agentLeg.setEndpointType(endpoint.getEndpointType());
        agentLeg.setDirection(CallDirection.OUTBOUND);
        agentLeg.setStatus(CallLegStatus.DIALING);
        agentLeg.setTarget(endpoint.getDialTarget());
        agentLeg.setAgentId(agentId);
        agentLeg.setInitiatedAt(Instant.now());
        return agentLeg;
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
}
