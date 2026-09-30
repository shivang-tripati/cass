package com.shivang.obd.telephony;

import com.shivang.obd.campaign.CallAttempt;
import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.campaign.CallAttemptStatus;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.call.HangupCauseMapper;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.dtmf.DtmfResultService;
import com.shivang.obd.voice.dtmf.DtmfResultType;
import com.shivang.obd.voice.media.DtmfCollectorTrigger;
import com.shivang.obd.voice.media.VoiceMediaController;
import com.shivang.obd.voice.media.PlaybackTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Processes FreeSWITCH ESL events and updates CallAttempt, CallSession, and CallLeg state.
 * <p>
 * Handles the full VB-1/VB-2 event set:
 * <ul>
 *   <li>CHANNEL_PROGRESS / CHANNEL_PROGRESS_MEDIA → RINGING</li>
 *   <li>CHANNEL_ANSWER → ANSWERED (playback is triggered by the dial service)</li>
 *   <li>PLAYBACK_START → PLAYING</li>
 *   <li>PLAYBACK_STOP → playback completed → campaign trigger (PLAYFILE:
 *       teardown; DTMF: begin collection)</li>
 *   <li>PLAYBACK_ERROR → playback failed → teardown + terminal failure</li>
 *   <li>CHANNEL_DTMF → DTMF digit → campaign collection boundary (VB-2)</li>
 *   <li>CHANNEL_HANGUP → finalize (authoritative reservation release)</li>
 * </ul>
 * All transitions are idempotent: events arriving after a terminal state are
 * ignored, and re-delivered progress events never regress state.
 */
@Service
@Slf4j
public class EslEventService {

    private final CallAttemptRepository attemptRepository;
    private final CallSessionRepository callSessionRepository;
    private final CallLegRepository callLegRepository;
    private final VoiceCapacityService voiceCapacity;
    private final VoiceMediaController mediaController;
    /**
     * All campaign playback triggers (PLAYFILE, DTMF, ...). Each trigger
     * self-guards by campaign type and no-ops for types it does not own,
     * so every bean sees the lifecycle events and exactly one acts.
     */
    private final java.util.List<PlaybackTrigger> playbackTriggers;
    private final java.util.Optional<DtmfCollectorTrigger> dtmfCollectorTrigger;
    private final DtmfResultService dtmfResultService;
    /**
     * VB-3: agent connect lifecycle — optional (agent-less deployments run
     * without it). All agent-leg events and bridge confirmations delegate
     * here; EslEventService only detects/correlates them.
     */
    private final java.util.Optional<com.shivang.obd.voice.agent.AgentConnectEvents> agentConnectEvents;
    /**
     * VB-4D: inbound call lifecycle — optional (outbound-only deployments
     * run without it). CHANNEL_CREATE and customer-leg events on inbound
     * (CONTACT_CENTER_INBOUND) sessions delegate here.
     */
    private final java.util.Optional<com.shivang.obd.voice.inbound.InboundCallEvents> inboundCallEvents;
    /**
     * VB-4E: agent-originated outbound lifecycle — optional (the service
     * is always present, but the boundary stays optional-shaped like its
     * inbound sibling so test harnesses can construct the event service
     * without it). Customer-leg events on attempt-less
     * CONTACT_CENTER_OUTBOUND sessions delegate here.
     */
    private final java.util.Optional<com.shivang.obd.voice.outbound.AgentOutboundCallService> outboundAgentCallService;
    /**
     * VB-8F — recovery for a campaign dispatch whose persistence was lost
     * (optional, like its three siblings, so existing harnesses and
     * outbound-only deployments construct this service unchanged).
     *
     * <p>Strictly secondary: consulted <em>only</em> after the primary
     * {@code providerCallId} correlation has definitively failed, and itself
     * gated on a validated campaign-outbound discriminator (see
     * {@link #orphanedCampaignAttemptId}).
     */
    private final java.util.Optional<com.shivang.obd.campaign.OrphanedDispatchRecovery>
            orphanRecovery;

    /**
     * The pre-VB-8F constructor, retained so every existing construction site
     * (and every existing test harness) is unchanged. Recovery is simply absent,
     * which is the correct degradation: events fall through to the pre-existing
     * "no attempt found" no-op.
     */
    public EslEventService(
            CallAttemptRepository attemptRepository,
            CallSessionRepository callSessionRepository,
            CallLegRepository callLegRepository,
            VoiceCapacityService voiceCapacity,
            VoiceMediaController mediaController,
            java.util.List<PlaybackTrigger> playbackTriggers,
            java.util.Optional<DtmfCollectorTrigger> dtmfCollectorTrigger,
            DtmfResultService dtmfResultService,
            java.util.Optional<com.shivang.obd.voice.agent.AgentConnectEvents> agentConnectEvents,
            java.util.Optional<com.shivang.obd.voice.inbound.InboundCallEvents> inboundCallEvents,
            java.util.Optional<com.shivang.obd.voice.outbound.AgentOutboundCallService>
                    outboundAgentCallService) {
        this(attemptRepository, callSessionRepository, callLegRepository, voiceCapacity,
                mediaController, playbackTriggers, dtmfCollectorTrigger, dtmfResultService,
                agentConnectEvents, inboundCallEvents, outboundAgentCallService,
                java.util.Optional.empty());
    }

    /**
     * The full constructor, used by Spring.
     *
     * <p>Written out explicitly rather than generated by
     * {@code @RequiredArgsConstructor} because this class now has two
     * constructors: with more than one and no {@code @Autowired}, container
     * injection falls back to looking for a no-arg constructor and fails the
     * whole application context.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public EslEventService(
            CallAttemptRepository attemptRepository,
            CallSessionRepository callSessionRepository,
            CallLegRepository callLegRepository,
            VoiceCapacityService voiceCapacity,
            VoiceMediaController mediaController,
            java.util.List<PlaybackTrigger> playbackTriggers,
            java.util.Optional<DtmfCollectorTrigger> dtmfCollectorTrigger,
            DtmfResultService dtmfResultService,
            java.util.Optional<com.shivang.obd.voice.agent.AgentConnectEvents> agentConnectEvents,
            java.util.Optional<com.shivang.obd.voice.inbound.InboundCallEvents> inboundCallEvents,
            java.util.Optional<com.shivang.obd.voice.outbound.AgentOutboundCallService>
                    outboundAgentCallService,
            java.util.Optional<com.shivang.obd.campaign.OrphanedDispatchRecovery>
                    orphanRecovery) {
        this.attemptRepository = attemptRepository;
        this.callSessionRepository = callSessionRepository;
        this.callLegRepository = callLegRepository;
        this.voiceCapacity = voiceCapacity;
        this.mediaController = mediaController;
        this.playbackTriggers = playbackTriggers;
        this.dtmfCollectorTrigger = dtmfCollectorTrigger;
        this.dtmfResultService = dtmfResultService;
        this.agentConnectEvents = agentConnectEvents;
        this.inboundCallEvents = inboundCallEvents;
        this.outboundAgentCallService = outboundAgentCallService;
        this.orphanRecovery = orphanRecovery;
    }

    /**
     * Sessions whose playback has been observed to actually START, keyed by
     * call session id (Phase D, J2).
     *
     * <p>{@code PLAYBACK_START} is the only positive evidence FreeSWITCH produces
     * that audio is playing. Remembering it is what lets a later
     * {@code CHANNEL_EXECUTE_COMPLETE} be read as success ("it started, and
     * finished") rather than as failure.
     *
     * <p>Bounded and self-clearing: a session is added on start and removed when
     * the call reaches a terminal state, so a long-running process cannot
     * accumulate entries. It is an observation aid, not a source of truth - the
     * session status remains authoritative.
     */
    private final java.util.Set<java.util.UUID> playbackStartedSessions =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Processes a single ESL event and updates the corresponding CallAttempt, CallSession, and CallLeg.
     *
     * @param event the ESL event to process
     * @return true if event was processed, false if ignored (unknown UUID, etc.)
     */
    @Transactional
    public boolean processEvent(EslEvent event) {
        String callUuid = event.getCallUuid();
        if (callUuid == null || callUuid.isBlank()) {
            // Phase D (J1): FreeSWITCH does not emit Call-UUID; the channel UUID
            // arrives as Channel-Call-UUID / Unique-ID. Reaching this branch now
            // means the event genuinely carries no channel identity, so it cannot
            // be correlated to a call and is dropped - as before, but the warning
            // names the headers actually searched so a future header change is
            // diagnosable rather than mysterious.
            log.warn("Received ESL event without a channel identity "
                    + "(searched {}), event={}",
                    EslEvent.CHANNEL_IDENTITY_HEADERS, event.getEventName());
            return false;
        }

        // VB-3: the provider UUID may be the CUSTOMER (attempt-scoped) or the
        // AGENT leg (agent legs carry their own originate UUID and never match
        // an attempt), so the agent-leg branch is checked FIRST.
        var sessionOpt = callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(callUuid);

        // VB-4D: a fresh inbound channel (CHANNEL_CREATE) creates the canonical
        // session — nothing can match before this. Call-Direction=inbound only
        // appears on channels FreeSWITCH received (not originated), so outbound
        // campaign channels are unaffected.
        if (sessionOpt.isEmpty() && "CHANNEL_CREATE".equals(event.getEventName())
                && "inbound".equalsIgnoreCase(event.getHeader("Call-Direction"))) {
            return handleInboundChannelCreate(event);
        }

        if (sessionOpt.isEmpty()) {
            var agentLegOpt = callLegRepository.findByProviderCallIdAndDeletedAtIsNull(callUuid)
                    .filter(leg -> leg.getLegType() == CallLegType.AGENT);
            if (agentLegOpt.isPresent()) {
                return processAgentLegEvent(agentLegOpt.get(), event);
            }
            // CHANNEL_BRIDGE is confirmed on the CALLER channel and correlated
            // via Bridge-B-Unique-ID — it never matches an attempt, so it must
            // be attempted before the attempt-miss early-return.
            if ("CHANNEL_BRIDGE".equals(event.getEventName())) {
                return handleBridgeEvent(event);
            }
        }

        // Find the call attempt by provider call ID
        var attemptOpt = attemptRepository.findByProviderCallIdAndDeletedAtIsNull(callUuid);
        if (attemptOpt.isEmpty()) {
            // VB-4D: inbound sessions never have a CallAttempt — their
            // customer-leg events are handled by the inbound boundary.
            if (sessionOpt.isPresent()
                    && sessionOpt.get().getCallType()
                            == com.shivang.obd.voice.call.CallType.CONTACT_CENTER_INBOUND) {
                return processInboundSessionEvent(sessionOpt.get(), event);
            }
            // VB-4E: agent-originated outbound sessions never have a
            // CallAttempt either — their customer-leg events are handled by
            // the outbound boundary.
            if (sessionOpt.isPresent()
                    && sessionOpt.get().getCallType()
                            == com.shivang.obd.voice.call.CallType.CONTACT_CENTER_OUTBOUND) {
                return processOutboundSessionEvent(sessionOpt.get(), event);
            }
            // VB-8F: primary correlation has definitively failed. Before giving
            // up, consider a validated recovery correlation for a campaign
            // dispatch whose dispatch transaction rolled back. This is the
            // only place it can run, so it is unreachable whenever the
            // providerCallId path resolves and can therefore never override it.
            var orphanedAttemptId = orphanedCampaignAttemptId(event, callUuid);
            if (orphanedAttemptId.isPresent()) {
                return orphanRecovery.get().recover(
                                orphanedAttemptId.get(), callUuid, event.getHangupCause())
                        ? true
                        : false;
            }
            log.debug("No call attempt found for providerCallId={} (event={})", callUuid, event.getEventName());
            return false;
        }

        CallAttempt attempt = attemptOpt.get();
        CallSession session = sessionOpt.orElse(null);
        CallLeg leg = null;
        if (session != null) {
            var legs = callLegRepository.findByCallSessionIdAndDeletedAtIsNull(session.getId());
            leg = legs.isEmpty() ? null : legs.get(0); // Primary leg (customer)
        }

        // Process based on event type
        switch (event.getEventName()) {
            case "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA" -> handleChannelProgress(attempt, session, leg);
            case "CHANNEL_ANSWER" -> handleChannelAnswer(attempt, session, leg);
            case "PLAYBACK_START" -> handlePlaybackStart(session, leg);
            case "PLAYBACK_STOP" -> handlePlaybackStop(attempt, session, leg, event);
            case "PLAYBACK_ERROR" -> handlePlaybackError(attempt, session, leg, event);
            // Phase D (J2): the request finished. If playback never started, the
            // accepted command produced no audio - a media failure.
            case "CHANNEL_EXECUTE_COMPLETE" -> handlePlaybackLifecycle(attempt, session, leg, event);
            case "CHANNEL_DTMF" -> {
                if (!handleChannelDtmf(attempt, session, event)) {
                    return false; // Malformed/unmatched DTMF event: ignored.
                }
            }
            case "CHANNEL_HANGUP" -> handleChannelHangup(attempt, session, leg, event);
            default -> {
                log.debug("Ignoring unhandled event type: {}", event.getEventName());
                return false;
            }
        }

        return true;
    }

    /**
     * VB-8F: the evidence that proves this event describes a campaign outbound
     * channel whose dispatch write was lost, and returns the attempt it belongs
     * to.
     *
     * <h2>Why UUID equality alone is not enough</h2>
     *
     * <p>Reading {@code Unique-ID} and looking up an attempt by it would be
     * unsafe: FreeSWITCH generates channel UUIDs itself for every channel it
     * receives or originates, across inbound, agent, browser and unrelated
     * domains. Equality with an attempt id proves nothing on its own.
     *
     * <p>What makes it safe is the conjunction below. A channel only carries
     * {@code variable_origination_uuid} when the platform originated it with an
     * explicitly pinned identity, and the platform pins one in exactly one
     * production path - the campaign customer leg, pinned to the attempt id.
     * Requiring that the pinned value <em>equals</em> the channel's own
     * identity proves the platform is the party that created this channel, and
     * that the identity is the attempt rather than a random value.
     *
     * <h2>Why each other domain is excluded</h2>
     *
     * <ul>
     *   <li><b>Inbound</b> — FreeSWITCH received the channel, so the platform
     *       never originated it and never set the pinned variable on it. This is
     *       why no direction header is required: the variable's <em>presence</em>
     *       already proves origination, which inbound channels cannot fake.</li>
     *   <li><b>Agent leg</b> — the platform does originate it with a pinned
     *       variable, but with a <em>random</em> UUID that is not an attempt id,
     *       so the lookup finds nothing and fails closed.</li>
     *   <li><b>Browser / WebRTC, unrelated channels</b> — not originated by this
     *       platform, so the pinned variable is absent.</li>
     *   <li><b>Channel belonging to another tenant, session or leg</b> — the
     *       returned id is only ever used to load that one persisted attempt,
     *       and the recovery re-reads and re-validates it. Tenant ownership comes
     *       from that row, never from the UUID.</li>
     * </ul>
     *
     * <p>Only {@code CHANNEL_HANGUP} qualifies. It is the terminal external
     * outcome, and settling from it needs no fabricated session. Earlier events
     * (answer, progress) for a lost dispatch are deliberately ignored: they
     * carry no final outcome, and if the hangup is ever lost, VB-8E's orphan
     * sweep remains the conservative backstop.
     */
    private java.util.Optional<java.util.UUID> orphanedCampaignAttemptId(
            EslEvent event, String callUuid) {
        if (orphanRecovery.isEmpty()) {
            return java.util.Optional.empty();
        }
        if (!"CHANNEL_HANGUP".equals(event.getEventName())) {
            return java.util.Optional.empty();
        }
        // VB-8F audit decision: a Call-Direction gate was considered here and
        // deliberately NOT added. This repository has no measured evidence that
        // FreeSWITCH reports Call-Direction on an originated channel's hangup
        // (the captured header dumps in EslEvent list variable_call_uuid and
        // variable_uuid, and the runtime contract's real-shape fixture carries
        // no direction header), so requiring one would make the recovery a
        // permanent silent no-op. The pinned-variable conjunction below already
        // excludes inbound, because the platform did not originate an inbound
        // channel and so never set its origination_uuid. Failing closed on a
        // header that might be absent is only safe if it is provably present.
        String originationUuid = event.getOriginationUuid();
        // Not an application-pinned channel: inbound, browser and unrelated
        // channels all stop here.
        if (originationUuid == null || originationUuid.isBlank()) {
            return java.util.Optional.empty();
        }
        // The platform must have pinned this very channel's identity. An agent
        // leg fails here or at the lookup: its pinned value is a random UUID.
        if (!originationUuid.equals(callUuid)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(java.util.UUID.fromString(originationUuid));
        } catch (IllegalArgumentException notAnAttemptId) {
            // The pinned identity is not UUID-shaped, so it cannot name an attempt.
            return java.util.Optional.empty();
        }
    }

    /**
     * CHANNEL_PROGRESS / CHANNEL_PROGRESS_MEDIA: destination is ringing
     * (progress media = early media). Idempotent: re-delivered progress never
     * regresses ANSWERED/terminal state.
     */
    private void handleChannelProgress(CallAttempt attempt, CallSession session, CallLeg leg) {
        // Only meaningful before answer; never regress an answered/terminal call.
        if (session == null || session.getStatus() != CallSessionStatus.DIALING) {
            return;
        }
        Instant now = Instant.now();
        session.setStatus(CallSessionStatus.RINGING);
        callSessionRepository.save(session);

        if (leg != null && leg.getStatus() == CallLegStatus.DIALING) {
            leg.setStatus(CallLegStatus.RINGING);
            callLegRepository.save(leg);
        }
        log.info("Call {} ringing (attempt={}, providerCallId={})",
                session.getId(), attempt.getId(), attempt.getProviderCallId());
    }

    /**
     * Handles CHANNEL_ANSWER event - call was answered.
     * Playback itself is triggered by the execution layer on this transition
     * (the media boundary is invoked by PlayfileExecutionService).
     * Idempotent: ANSWERED → ANSWERED is a no-op.
     */
    private void handleChannelAnswer(CallAttempt attempt, CallSession session, CallLeg leg) {
        Instant now = Instant.now();

        // Update CallSession — idempotent: don't regress PLAYING/terminal.
        // Fires the playback trigger only on the real DIALING/RINGING →
        // ANSWERED transition; duplicate ANSWER events are no-ops.
        boolean transitionedToAnswered = false;
        if (session != null && (session.getStatus() == CallSessionStatus.DIALING
                || session.getStatus() == CallSessionStatus.RINGING)) {
            session.setStatus(CallSessionStatus.ANSWERED);
            session.setAnsweredAt(now);
            callSessionRepository.save(session);
            transitionedToAnswered = true;
        }

        // Update CallLeg
        if (leg != null && (leg.getStatus() == CallLegStatus.DIALING
                || leg.getStatus() == CallLegStatus.RINGING)) {
            leg.setStatus(CallLegStatus.ANSWERED);
            leg.setAnsweredAt(now);
            callLegRepository.save(leg);
        }

        // Update CallAttempt (QUEUED -> IN_PROGRESS if the answer beat the
        // dialer's optimistic transition; IN_PROGRESS stays).
        boolean attemptWasQueued = attempt.getStatus() == CallAttemptStatus.QUEUED;
        if (attemptWasQueued) {
            attempt.setStatus(CallAttemptStatus.IN_PROGRESS);
            attempt.setStartedAt(now);
            attemptRepository.save(attempt);
        }
        log.info("CHANNEL_ANSWER for attempt {} (providerCallId={}) - call answered",
                attempt.getId(), attempt.getProviderCallId());

        // VB-1: playback may now start. Fire the execution-layer trigger only
        // on the real DIALING/RINGING → ANSWERED transition (idempotent —
        // duplicate ANSWER events must not re-trigger playback).
        if (transitionedToAnswered) {
            for (PlaybackTrigger trigger : playbackTriggers) {
                try {
                    trigger.onAnswered(session.getId(), attempt.getId());
                } catch (Exception e) {
                    // Playback trigger failure must not break event processing;
                    // PLAYBACK_ERROR/CHANNEL_HANGUP paths still finalize the call.
                    log.warn("Playback trigger failed for attempt {}: {}", attempt.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * PLAYBACK_START: media playback began on the channel. Transition
     * ANSWERED → PLAYING. Idempotent: duplicate PLAYBACK_START is a no-op.
     *
     * <p>Phase D: also records that playback actually started, which is the only
     * positive evidence of playback that FreeSWITCH produces.
     */
    private void handlePlaybackStart(CallSession session, CallLeg leg) {
        if (session != null) {
            playbackStartedSessions.add(session.getId());
        }
        if (session == null || session.getStatus() != CallSessionStatus.ANSWERED) {
            return; // Not yet answered (or already playing/terminal): ignore.
        }
        session.setStatus(CallSessionStatus.PLAYING);
        callSessionRepository.save(session);
        log.info("Playback started (callSession={})", session.getId());
    }

    /**
     * The playback lifecycle, established from the live event stream rather than
     * from an assumed error header (Phase D, J2).
     *
     * <h2>What the running system actually does</h2>
     *
     * <p>Measured on the live switch, for a single {@code uuid_broadcast} on an
     * answered channel:
     *
     * <pre>
     * valid file    -&gt; CHANNEL_EXECUTE, PLAYBACK_START, PLAYBACK_STOP, CHANNEL_EXECUTE_COMPLETE
     * missing file  -&gt; CHANNEL_EXECUTE, CHANNEL_EXECUTE_COMPLETE
     * </pre>
     *
     * <p>Both cases answer {@code +OK Message sent}. <b>No {@code PLAYBACK_ERROR}
     * event and no {@code Playback-Error} header is produced for a missing
     * file</b> - the failure appears only in the FreeSWITCH log, which the
     * platform does not read. The previous implementation depended on that
     * header, so its playback-failure branch was unreachable and a call with a
     * missing asset sat in ANSWERED until the maximum-duration sweeper killed
     * it, recorded as {@code MAX_DURATION_EXCEEDED} rather than as the media
     * failure it actually was.
     *
     * <h2>The rule</h2>
     *
     * <p>{@code CHANNEL_EXECUTE_COMPLETE} closes the request. If playback had
     * started by then, the request succeeded. If it had not, the request
     * completed <em>without ever playing</em> - which, given the command was
     * accepted, is a media failure. This is the discriminator the live system
     * actually provides.
     */
    private void handlePlaybackLifecycle(CallAttempt attempt, CallSession session,
                                         CallLeg leg, EslEvent event) {
        boolean started = playbackStartedSessions.contains(session.getId());
        if (started) {
            return; // Playback began; PLAYBACK_STOP owns completion.
        }
        log.warn("Playback request completed without ever starting "
                        + "(callSession={}, attempt={}, event={}) - treating as media failure",
                session.getId(), attempt.getId(), event.getEventName());
        handlePlaybackFailure(attempt, session, leg,
                "playback did not start; FreeSWITCH accepted the command but no "
                        + "PLAYBACK_START was emitted");
    }

    /**
     * Records a media failure on the session and requests channel teardown.
     *
     * <p>Shared by the (still-supported) {@code PLAYBACK_ERROR} branch and by the
     * {@code CHANNEL_EXECUTE_COMPLETE}-without-start branch, so both reach the
     * same classification and the same single release path. Reservation release
     * remains on the hangup path.
     */
    private void handlePlaybackFailure(CallAttempt attempt, CallSession session,
                                       CallLeg leg, String reason) {
        if (session == null) {
            return;
        }
        // Idempotent: only the first failure for a live, in-playback call acts.
        // A recorded failureCode marks the failure as already handled, so
        // duplicate events are no-ops; terminal attempts are guarded too.
        if (attempt.getStatus() == CallAttemptStatus.COMPLETED
                || attempt.getStatus() == CallAttemptStatus.FAILED
                || attempt.getStatus() == CallAttemptStatus.CANCELLED
                || session.getFailureCode() != null
                || (session.getStatus() != CallSessionStatus.PLAYING
                        && session.getStatus() != CallSessionStatus.ANSWERED)) {
            return;
        }
        log.warn("Playback failed (callSession={}, attempt={}, reason={})",
                session.getId(), attempt.getId(), reason);

        if (session.getStatus() == CallSessionStatus.PLAYING
                || session.getStatus() == CallSessionStatus.ANSWERED) {
            session.setFailureCode(PLAYBACK_FAILED_CODE);
            session.setFailureReason("Playback failed: " + reason);
            callSessionRepository.save(session);
        }

        // Request channel teardown; the CHANNEL_HANGUP event releases the
        // reservation and finalizes the attempt with our failure code.
        try {
            mediaController.terminateCall(session.getId(), session.getProviderCallId());
        } catch (Exception e) {
            log.warn("Teardown after playback failure failed for callSession {}: {}",
                    session.getId(), e.getMessage());
        }
    }

    /**
     * PLAYBACK_STOP: playback completed on the channel. Transition to
     * PLAYBACK_COMPLETED (distinct from ANSWERED so playback can never
     * restart) and request hangup via the media boundary — the authoritative
     * reservation release happens on the CHANNEL_HANGUP event, preserving the
     * single release path (VB-0). Idempotent: a second PLAYBACK_STOP for the
     * same call is a no-op because the session has already left PLAYING.
     */
    private void handlePlaybackStop(CallAttempt attempt, CallSession session, CallLeg leg, EslEvent event) {
        if (session == null || session.getStatus() != CallSessionStatus.PLAYING) {
            return; // Duplicate or out-of-order completion: no-op.
        }
        session.setStatus(CallSessionStatus.PLAYBACK_COMPLETED);
        callSessionRepository.save(session);
        log.info("Playback completed (callSession={}, attempt={})",
                session.getId(), attempt.getId());

        // Post-completion handling is campaign-type specific and event-driven
        // (VB-1 PLAYFILE → teardown; VB-2 DTMF → begin collection). Firing on
        // exactly this PLAYING → PLAYBACK_COMPLETED transition keeps the
        // dispatch idempotent: duplicate PLAYBACK_STOP events are filtered by
        // the guard above and never re-trigger. The CHANNEL_HANGUP event that
        // eventually follows remains the single authoritative release path for
        // the capacity reservation.
        if (!playbackTriggers.isEmpty()) {
            for (PlaybackTrigger trigger : playbackTriggers) {
                try {
                    trigger.onPlaybackCompleted(session.getId(), attempt.getId());
                } catch (Exception e) {
                    log.warn("Playback-completed trigger failed for attempt {}: {}",
                            attempt.getId(), e.getMessage());
                }
            }
        }
        // Without a campaign trigger (non-campaign call) there is nothing to
        // do post-playback; the channel's own hangup event finalizes state.
    }

    /**
     * CHANNEL_DTMF: a DTMF digit was reported on the channel (VB-2).
     * Correlation is by Call-UUID (as for every event); the digit is
     * forwarded to the campaign DTMF collection boundary, which owns
     * interaction state, validation and idempotency. Events without a
     * parseable digit, or for calls with no voice session, are safely
     * ignored.
     */
    private boolean handleChannelDtmf(CallAttempt attempt, CallSession session, EslEvent event) {
        String digit = event.getDtmfDigit();
        if (digit == null || digit.isBlank()) {
            log.debug("CHANNEL_DTMF without digit for attempt {} — ignoring", attempt.getId());
            return false;
        }
        if (session == null) {
            log.debug("CHANNEL_DTMF for attempt {} has no call session — ignoring", attempt.getId());
            return false;
        }
        log.info("DTMF digit received (callSession={}, attempt={}, digit={})",
                session.getId(), attempt.getId(), digit);
        dtmfCollectorTrigger.ifPresent(trigger -> {
            try {
                trigger.onDtmfDigit(session.getId(), attempt.getId(), digit.trim());
            } catch (Exception e) {
                // A DTMF processing failure must not break event processing;
                // the timeout path and hangup path still finalize the call.
                log.warn("DTMF digit processing failed for attempt {}: {}",
                        attempt.getId(), e.getMessage());
            }
        });
        return true;
    }

    /**
     * PLAYBACK_ERROR: media playback failed. Records the failure, requests
     * channel teardown, and marks the attempt failed. Retryability is decided
     * by the existing retry model: PLAYBACK_FAILED is classified as
     * TEMPORARY (FreeSWITCH resource/media errors are typically transient —
     * e.g. codec/resource exhaustion) and follows the existing requeue
     * semantics. Reservation release remains on the hangup path.
     *
     * <p>Phase D: retained, but no longer the <em>assumed</em> failure signal.
     * This event is not produced for a missing file, so it cannot be the only
     * path - see {@link #handlePlaybackLifecycle}.
     */
    private void handlePlaybackError(CallAttempt attempt, CallSession session, CallLeg leg, EslEvent event) {
        String reason = event.getHeader("Playback-Error") != null
                ? event.getHeader("Playback-Error")
                : "media playback error";
        handlePlaybackFailure(attempt, session, leg, reason);
    }

    /**
     * VB-3: ESL events on an AGENT-type leg (correlated by the leg's own
     * provider call UUID — agent channels never match an attempt). Delegates
     * the lifecycle transition to the agent connect boundary; unknown agent
     * UUIDs are ignored safely, and all processing failures are contained so
     * the shared event loop never breaks.
     */
    private boolean processAgentLegEvent(CallLeg agentLeg, EslEvent event) {
        var connectEvents = agentConnectEvents.orElse(null);
        if (connectEvents == null) {
            log.debug("Agent leg event {} but no agent connect boundary — ignoring", event.getEventName());
            return false;
        }
        try {
            switch (event.getEventName()) {
                case "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA" ->
                        connectEvents.onAgentLegRinging(agentLeg);
                case "CHANNEL_ANSWER" -> connectEvents.onAgentLegAnswered(agentLeg);
                case "CHANNEL_HANGUP" ->
                        connectEvents.onAgentLegHangup(agentLeg, event.getHangupCause());
                default -> {
                    log.debug("Ignoring agent-leg event type: {}", event.getEventName());
                    return false;
                }
            }
        } catch (Exception e) {
            log.warn("Agent connect event processing failed (leg={}, event={}): {}",
                    agentLeg.getId(), event.getEventName(), e.getMessage());
        }
        return true;
    }

    /**
     * VB-3: CHANNEL_BRIDGE confirms a caller↔agent bridge — the authoritative
     * bridge-established signal (command acceptance is NOT confirmation).
     * Correlated by Bridge-B-Unique-ID (the agent channel); the event's own
     * Call-UUID is the bridge anchor. Unmatched bridges are ignored.
     */
    private boolean handleBridgeEvent(EslEvent event) {
        String agentUuid = event.getBridgeBUuid();
        if (agentUuid == null || agentUuid.isBlank()) {
            log.debug("CHANNEL_BRIDGE without Bridge-B-Unique-ID — ignoring");
            return false;
        }
        var agentLegOpt = callLegRepository.findByProviderCallIdAndDeletedAtIsNull(agentUuid)
                .filter(leg -> leg.getLegType() == CallLegType.AGENT);
        if (agentLegOpt.isEmpty()) {
            log.debug("CHANNEL_BRIDGE for non-agent channel {} — ignoring", agentUuid);
            return false;
        }
        var connectEvents = agentConnectEvents.orElse(null);
        if (connectEvents == null) {
            return false;
        }
        try {
            connectEvents.onBridgeConfirmed(agentLegOpt.get().getCallSessionId(), agentUuid);
        } catch (Exception e) {
            log.warn("Bridge confirmation processing failed (callSession={}): {}",
                    agentLegOpt.get().getCallSessionId(), e.getMessage());
        }
        return true;
    }

    /**
     * Handles CHANNEL_HANGUP event - call ended.
     * Determines outcome from hangup cause and updates CallAttempt, CallSession, and CallLeg.
     */
    private void handleChannelHangup(CallAttempt attempt, CallSession session, CallLeg leg, EslEvent event) {
        Instant now = Instant.now();

        // Skip if CallAttempt already terminal
        if (attempt.getStatus() == CallAttemptStatus.COMPLETED
                || attempt.getStatus() == CallAttemptStatus.FAILED
                || attempt.getStatus() == CallAttemptStatus.CANCELLED) {
            log.debug("CHANNEL_HANGUP for attempt {} already terminal ({}), ignoring",
                    attempt.getId(), attempt.getStatus());
            return;
        }

        String hangupCause = event.getHangupCause();
        log.info("CHANNEL_HANGUP for attempt {} (providerCallId={}, cause={})",
                attempt.getId(), attempt.getProviderCallId(), hangupCause);

        // Any failure recorded on the session before hangup (transient
        // PLAYBACK_FAILED, permanent PLAYBACK_CONFIG_INVALID, permanent
        // DTMF_CONFIG_INVALID, transient DTMF_PLAYBACK_FAILED) means the call
        // did not fulfill its mission — the attempt must fail with the
        // recorded classification regardless of the resulting hangup cause. A
        // NORMAL_CLEARING teardown after a failed/absent playback or a DTMF
        // configuration error must never complete the attempt as if the call
        // succeeded. Preserve the specific code — do not collapse classes.
        String recordedFailureCode = session != null ? session.getFailureCode() : null;
        boolean inCallFailureRecorded = recordedFailureCode != null && !recordedFailureCode.isBlank();

        // Determine outcome based on hangup cause
        boolean success = !inCallFailureRecorded && isSuccessfulCompletion(hangupCause);
        String failureCode = inCallFailureRecorded
                ? recordedFailureCode
                : HangupCauseMapper.toFailureCode(hangupCause);
        String failureReason = inCallFailureRecorded
                ? session.getFailureReason()
                : "Hangup cause: " + hangupCause;

        if (success) {
            attempt.setStatus(CallAttemptStatus.COMPLETED);
            attempt.setFailureCode(null);
            attempt.setFailureReason(null);
            log.info("Call attempt {} completed successfully (cause={})", attempt.getId(), hangupCause);
        } else {
            attempt.setStatus(CallAttemptStatus.FAILED);
            attempt.setFailureCode(failureCode);
            attempt.setFailureReason(failureReason);
            log.info("Call attempt {} failed (cause={})", attempt.getId(), hangupCause);
        }
        attempt.setCompletedAt(now);
        // Phase D (J2): the call is over, so the playback-start observation is
        // no longer meaningful. Clearing here keeps the tracker bounded.
        playbackStartedSessions.remove(session == null ? attempt.getId() : session.getId());

        // Update CallSession
        if (session != null) {
            session.setStatus(success ? CallSessionStatus.COMPLETED : CallSessionStatus.FAILED);
            session.setEndedAt(now);
            if (!success) {
                session.setFailureCode(failureCode);
                session.setFailureReason(failureReason);
            }
            callSessionRepository.save(session);
        }

        // Update CallLeg
        if (leg != null) {
            leg.setStatus(success ? CallLegStatus.COMPLETED : CallLegStatus.FAILED);
            leg.setEndedAt(now);
            leg.setFailureCode(success ? null : failureCode);
            leg.setFailureReason(success ? null : failureReason);
            callLegRepository.save(leg);
        }

        // Release capacity reservation — the single authoritative release path.
        if (session != null && session.getGatewayId() != null && attempt.getTenantId() != null) {
            voiceCapacity.release(session.getGatewayId(), attempt.getTenantId());
            log.debug("Released capacity reservation for gateway {} tenant {}",
                    session.getGatewayId(), attempt.getTenantId());
        }

        // VB-2: a call that ends while DTMF collection is still in progress
        // (remote hangup while waiting) leaves a terminal ABANDONED record —
        // no interaction result is ever left dangling non-terminal. Idempotent
        // via the atomic claim; duplicate hangups are already filtered above.
        if (session != null) {
            try {
                dtmfResultService.abandonIfCollecting(session.getId());
            } catch (Exception e) {
                log.warn("DTMF abandon marking failed for callSession {}: {}",
                        session.getId(), e.getMessage());
            }
        }

        // VB-3: caller hangup during the agent connect flow — release the
        // agent reservation and terminate the agent leg at any point of the
        // flow (selection/reserved/ringing/bridging/bridged). Idempotent:
        // no reservation is a no-op, and duplicate hangups never reach here.
        if (session != null && agentConnectEvents.isPresent()) {
            try {
                agentConnectEvents.get().onCallerHangup(session.getId());
            } catch (Exception e) {
                log.warn("Agent cleanup after caller hangup failed for callSession {}: {}",
                        session.getId(), e.getMessage());
            }
        }
    }

    /**
     * VB-4D: a fresh inbound channel creates the canonical session via the
     * inbound boundary. Only events carrying Call-Direction=inbound reach
     * here (checked by the caller); unroutable/unknown DIDs are ignored by
     * the boundary (fail closed). Contained: the shared loop never breaks.
     */
    /**
     * VB-4E: lifecycle events on an agent-originated outbound session's
     * customer channel (these sessions have no CallAttempt). PROGRESS →
     * ringing; ANSWER → outbound boundary (which originates the agent leg
     * and hands over to the shared VB-3 connect flow); HANGUP → outbound
     * boundary (authoritative finalize + cleanup + VB-0 capacity release).
     * Contained so the shared loop never breaks.
     */
    private boolean processOutboundSessionEvent(
            com.shivang.obd.voice.call.CallSession session, EslEvent event) {
        var outbound = outboundAgentCallService.orElse(null);
        if (outbound == null) {
            return false;
        }
        try {
            var customerLegOpt = callLegRepository
                    .findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                            session.getId(), com.shivang.obd.voice.call.CallLegType.CUSTOMER)
                    .stream().findFirst();
            if (customerLegOpt.isEmpty()) {
                return false;
            }
            var customerLeg = customerLegOpt.get();
            switch (event.getEventName()) {
                case "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA" ->
                        outbound.onCustomerLegRinging(customerLeg);
                case "CHANNEL_ANSWER" -> outbound.onCustomerLegAnswered(customerLeg);
                case "CHANNEL_HANGUP" ->
                        outbound.onCustomerLegHangup(
                                customerLeg, event.getHangupCause(), session.getTenantId());
                case "CHANNEL_BRIDGE" -> {
                    // The bridge is anchored on the customer channel (which
                    // matches this session), so the shared VB-3 correlation
                    // (Bridge-B-Unique-ID → agent leg) runs from here.
                    boolean processed = handleBridgeEvent(event);
                    if (!processed) {
                        return false;
                    }
                }
                default -> {
                    log.debug("Ignoring outbound session event type: {}",
                            event.getEventName());
                    return false;
                }
            }
        } catch (Exception e) {
            log.warn("Outbound session event processing failed (callSession={}, event={}): {}",
                    session.getId(), event.getEventName(), e.getMessage());
        }
        return true;
    }

    private boolean handleInboundChannelCreate(EslEvent event) {
        var inbound = inboundCallEvents.orElse(null);
        if (inbound == null) {
            log.debug("Inbound CHANNEL_CREATE but no inbound boundary — ignoring");
            return false;
        }
        try {
            inbound.onInboundChannelCreated(
                    event.getCallUuid(),
                    event.getHeader("Caller-Destination-Number"),
                    event.getHeader("Caller-Caller-ID-Number"));
            return true;
        } catch (Exception e) {
            log.warn("Inbound CHANNEL_CREATE processing failed (channel={}): {}",
                    event.getCallUuid(), e.getMessage());
            return false;
        }
    }

    /**
     * VB-4D: lifecycle events on an inbound session's caller channel.
     * ANSWER → inbound boundary; HANGUP → inbound boundary (authoritative
     * end-of-call cleanup). Contained so the shared loop never breaks.
     */
    private boolean processInboundSessionEvent(
            com.shivang.obd.voice.call.CallSession session, EslEvent event) {
        var inbound = inboundCallEvents.orElse(null);
        if (inbound == null) {
            return false;
        }
        try {
            var callerLegOpt = callLegRepository
                    .findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                            session.getId(), com.shivang.obd.voice.call.CallLegType.CUSTOMER)
                    .stream().findFirst();
            if (callerLegOpt.isEmpty()) {
                return false;
            }
            var callerLeg = callerLegOpt.get();
            switch (event.getEventName()) {
                case "CHANNEL_ANSWER" -> inbound.onInboundCallerAnswered(callerLeg);
                case "CHANNEL_HANGUP" ->
                        inbound.onInboundCallerHangup(callerLeg, event.getHangupCause());
                default -> {
                    log.debug("Ignoring inbound session event type: {}",
                            event.getEventName());
                    return false;
                }
            }
        } catch (Exception e) {
            log.warn("Inbound session event processing failed (callSession={}, event={}): {}",
                    session.getId(), event.getEventName(), e.getMessage());
        }
        return true;
    }

    /**
     * Determines if a hangup cause indicates successful completion.
     * FreeSWITCH hangup causes: https://freeswitch.org/confluence/display/FREESWITCH/Hangup+Causes
     */
    private boolean isSuccessfulCompletion(String hangupCause) {
        // VB-6D.1: a normal release is an outcome, not a failure. Delegated to
        // the shared boundary so this adapter and the agent-outbound adapter
        // cannot disagree about the same provider event.
        return HangupCauseMapper.isNormalClearing(hangupCause);
    }

    /** Failure code recorded on session/attempt when media playback fails (temporary). */
    public static final String PLAYBACK_FAILED_CODE = "PLAYBACK_FAILED";

    /**
     * Failure code recorded when playback is impossible due to campaign/asset
     * configuration (missing/unapproved/not-tenant-owned asset) — permanent.
     * Kept in sync with {@code PlayfileExecutionService.PLAYBACK_CONFIG_INVALID_CODE}.
     */
    public static final String PLAYBACK_CONFIG_INVALID_CODE = "PLAYBACK_CONFIG_INVALID";
}
