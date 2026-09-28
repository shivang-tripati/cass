package com.shivang.obd.campaign;

import com.shivang.obd.campaign.config.MissedCallCampaignConfig;
import com.shivang.obd.campaign.config.MissedCallRingWindow;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.media.PlaybackTrigger;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MISSED_CALL campaign execution (VB-7B) — the minimal runtime seam.
 *
 * <h2>What a MISSED_CALL call is</h2>
 *
 * <pre>
 * dial the contact → the callee's phone rings → the platform ends the call
 * </pre>
 *
 * <p>No media, no DTMF, no IVR, no agent, no queue, no bridge. The originate
 * command carries no media argument, so "playing nothing" is not an
 * implementation burden — it is the absence of a call to {@code playFile}. What
 * this class adds is the two things a ring does need: a time budget, and
 * platform-controlled termination at the end of it.
 *
 * <h2>Why success is a deliberate termination</h2>
 *
 * <p>The call is not "delivered" by ringing and drifting — the platform decides
 * when it ends, and it ends it with the same normal-clearing teardown every other
 * campaign path uses. {@code HangupCauseMapper} maps that to cause 16, and
 * {@code EslEventService} maps cause 16 to {@code COMPLETED} with no failure
 * code. That is the whole success definition (locked OD-1), and it is why this
 * class must <b>not</b> record a failure code before asking for the teardown:
 * the recorded-code path has precedence in the classifier and would turn a
 * successful ring into a retryable failure.
 *
 * <p>Carrier-reported no-answer (cause 19) is deliberately left alone. It remains
 * {@code NO_ANSWER} → {@code FAILED} → {@code TEMPORARY} → existing retry rules.
 * The platform is not reinterpreting the carrier's taxonomy.
 *
 * <h2>Two phases, one budget</h2>
 *
 * <ul>
 *   <li><b>Pre-answer</b> — {@link #terminateExpiredRingWindows()} finds a
 *       dispatched attempt whose budget has elapsed, stamps the deadline and
 *       terminates, in one transaction, so the deadline is never observable
 *       without the teardown that follows it.</li>
 *   <li><b>Post-answer</b> — {@link #onAnswered} <em>rebases</em> the deadline onto
 *       the answer instant, so an answered call is never terminated on the
 *       pre-answer schedule and never waits for stale recovery (locked OD-2).
 *       The same sweep then terminates it at that deadline.</li>
 * </ul>
 *
 * <h2>It is a PlaybackTrigger for one reason only</h2>
 *
 * <p>{@code PlaybackTrigger} is this codebase's established CHANNEL_ANSWER seam
 * into the campaign execution layer, and its own contract says implementations
 * act only for the types they own so several beans coexist without dispatch
 * ambiguity. {@code DtmfExecutionService} is a {@code PlaybackTrigger} for the
 * same reason — it also plays nothing. Nothing about "playback" is used here.
 *
 * <h2>Scheduling</h2>
 *
 * <p>There is no {@code @Scheduled} component in this class. The sweep is invoked
 * from the existing {@link CampaignExecutionOrchestrator} tick, in its own
 * failure boundary, so a MISSED_CALL timeout can neither add a scheduler nor
 * delay any other step.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MissedCallExecutionService implements PlaybackTrigger, CallSessionDeadlineOwner {

    /** Maximum sessions examined per sweep. Bounded, like every other sweep here. */
    static final int SWEEP_BATCH = 200;

    /**
     * The pre-answer budget's earliest possible expiry. Used only as a cheap,
     * over-inclusive SQL pre-filter so the sweep does not have to resolve a
     * snapshot for attempts that cannot possibly be due yet.
     */
    static final int PRE_FILTER_SECONDS = MissedCallRingWindow.MIN_RING_SECONDS;

    private final CallSessionRepository callSessionRepository;
    private final CallAttemptRepository callAttemptRepository;
    private final CampaignExecutionRepository executionRepository;
    private final CampaignRuntimeConfigResolver runtimeConfigResolver;
    private final CallSessionDeadlineAuthority deadlineAuthority;
    private final VoiceMediaController mediaController;

    // ------------------------------------------------------------------
    // CHANNEL_ANSWER: post-answer phase (OD-2)
    // ------------------------------------------------------------------

    @Override
    public void onAnswered(UUID callSessionId, UUID attemptId) {
        if (callSessionId == null || attemptId == null) {
            return;
        }
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(callSessionId)
                .orElse(null);
        if (session == null) {
            return;
        }
        // Idempotency: only the first ANSWERED transition establishes the
        // post-answer deadline. A duplicate CHANNEL_ANSWER, or a call already
        // being torn down, is a no-op - no second timer, no second terminal
        // transition.
        if (session.getStatus() != CallSessionStatus.ANSWERED) {
            log.debug("MISSED_CALL trigger: session {} in state {} - ignoring",
                    callSessionId, session.getStatus());
            return;
        }
        CallAttempt attempt = callAttemptRepository.findByIdAndDeletedAtIsNull(attemptId)
                .orElse(null);
        if (attempt == null) {
            return;
        }
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig config =
                resolveExecutionConfig(attempt);
        if (config == null) {
            return;
        }
        if (config.campaignType() != CampaignType.MISSED_CALL) {
            log.debug("Campaign {} is {} - not MISSED_CALL, no ring budget applied",
                    config.campaignId(), config.campaignType());
            return;
        }
        if (config.asMissedCall().isEmpty()) {
            log.warn("MISSED_CALL campaign {} reached answer with no readable ring "
                    + "configuration in its snapshot", config.campaignId());
            return;
        }

        // Rebased onto the answer instant. This is what stops an answered call
        // from retaining its pre-answer deadline, and what stops it from waiting
        // for the stale-attempt recovery sweep.
        int ring = config.asMissedCall().orElseThrow().effectiveRingSeconds();
        boolean stamped = deadlineAuthority.applyDeadline(
                session, CallSessionDeadlineAuthority.Anchor.ANSWERED, ring, true);
        log.info("MISSED_CALL answered (callSession={}, ring={}s, deadlineStamped={})",
                callSessionId, ring, stamped);
        // Intentionally no playback, no collection, no bridge: a MISSED_CALL call
        // stays connected and silent until the sweep terminates it.
    }

    /**
     * {@code onPlaybackCompleted} is a no-op: this campaign type plays nothing, so
     * there is no playback completion to act on. The hangup that ends the call is
     * what finalises it, through the shared attempt path.
     */
    @Override
    public void onPlaybackCompleted(UUID callSessionId, UUID attemptId) {
        // intentionally empty
    }

    // ------------------------------------------------------------------
    // Termination sweep (OD-1 / OD-2) - invoked by the existing orchestrator tick
    // ------------------------------------------------------------------

    /**
     * Terminates every MISSED_CALL call whose budget has elapsed, in both phases,
     * without recording a failure code so the teardown classifies as COMPLETED.
     *
     * <p>Called from the existing {@code CampaignExecutionOrchestrator.scheduledTick}
     * in its own failure boundary. There is deliberately no {@code @Scheduled}
     * annotation here.
     *
     * @return the number of calls this pass asked to terminate
     */
    public int terminateExpired() {
        int terminated = 0;
        for (CallSession session : expiredStampedSessions()) {
            if (terminateStamped(session.getId(), "ring window elapsed")) {
                terminated++;
            }
        }
        for (CallSession session : expiredUnansweredSessions()) {
            if (terminateUnanswered(session.getId())) {
                terminated++;
            }
        }
        if (terminated > 0) {
            log.info("MISSED_CALL: terminated {} call(s) whose ring window elapsed",
                    terminated);
        }
        return terminated;
    }

    /**
     * Post-answer phase: sessions already carrying a deadline this policy owns
     * and whose deadline has passed. Found with the persisted column, so this is
     * an indexed range scan.
     */
    private List<CallSession> expiredStampedSessions() {
        return callSessionRepository.findLiveSessionsWithExpiredDeadline(
                LIVE_STATUS_NAMES, Instant.now(), org.springframework.data.domain.PageRequest
                        .of(0, SWEEP_BATCH));
    }

    /**
     * Pre-answer phase: dispatched attempts with no deadline yet, old enough that
     * <em>some</em> legal budget might have elapsed. The exact budget is resolved
     * per candidate below; the pre-filter only guarantees nothing due is missed.
     */
    private List<CallSession> expiredUnansweredSessions() {
        return callSessionRepository.findLiveUnansweredSessionsDispatchedBefore(
                LIVE_STATUS_NAMES, Instant.now().minusSeconds(PRE_FILTER_SECONDS),
                org.springframework.data.domain.PageRequest.of(0, SWEEP_BATCH));
    }

    /**
     * Terminates a session that already carries a deadline this policy owns.
     * No failure code is recorded: that is what makes the resulting
     * normal-clearing hangup classify as a completed delivery.
     */
    @Transactional
    public boolean terminateStamped(UUID sessionId, String why) {
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(sessionId)
                .orElse(null);
        if (session == null || !ownsDeadline(session)) {
            return false;
        }
        if (session.getDeadlineAt() == null
                || session.getDeadlineAt().isAfter(Instant.now())) {
            return false;
        }
        if (session.getFailureCode() != null && !session.getFailureCode().isBlank()) {
            return false; // a terminal path already recorded an outcome
        }
        log.info("MISSED_CALL terminating session {} ({})", sessionId, why);
        return requestTeardown(session);
    }

    /**
     * Pre-answer termination. Stamps the deadline and asks for the teardown in
     * the same transaction, so an observer can never see a stamped-but-unterminated
     * session and race this policy with the maximum-duration reconciler.
     */
    @Transactional
    public boolean terminateUnanswered(UUID sessionId) {
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(sessionId)
                .orElse(null);
        if (session == null || session.getDeadlineAt() != null) {
            return false; // already handled, or the post-answer phase owns it
        }
        if (!LIVE_STATUSES.contains(session.getStatus())) {
            return false;
        }
        if (session.getFailureCode() != null && !session.getFailureCode().isBlank()) {
            return false;
        }
        CallAttempt attempt = attemptForSession(session);
        if (attempt == null) {
            return false;
        }
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig config =
                resolveExecutionConfig(attempt);
        if (config == null || config.campaignType() != CampaignType.MISSED_CALL) {
            return false; // not ours: leave it entirely alone
        }
        int ring = config.asMissedCall()
                .map(MissedCallCampaignConfig::effectiveRingSeconds)
                .orElse(MissedCallRingWindow.DEFAULT_RING_SECONDS);
        Instant from = session.getInitiatedAt() != null
                ? session.getInitiatedAt() : attempt.getStartedAt();
        if (from == null || !MissedCallRingWindow.isExpired(from, ring)) {
            return false; // not due yet - a longer configured budget still applies
        }
        deadlineAuthority.applyDeadline(
                session, CallSessionDeadlineAuthority.Anchor.SESSION_START, ring, true);
        log.info("MISSED_CALL terminating unanswered session {} after {}s", sessionId, ring);
        return requestTeardown(session);
    }

    /**
     * Asks the media boundary to end the call. Best effort and never fatal: the
     * channel may already be gone, and a teardown failure must not leave the
     * sweep thread broken. The deadline already stamped above is what makes the
     * next pass retry idempotently.
     */
    private boolean requestTeardown(CallSession session) {
        try {
            mediaController.terminateCall(session.getId(), session.getProviderCallId());
            return true;
        } catch (RuntimeException e) {
            log.warn("MISSED_CALL teardown for session {} failed: {}",
                    session.getId(), e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Deadline ownership
    // ------------------------------------------------------------------

    /**
     * This policy owns a deadline it stamped, and no other. A session is
     * identified by carrying a deadline and belonging to a MISSED_CALL execution,
     * so the maximum-duration reconciler can decline it and PLAYFILE's cap stays
     * entirely its own business.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean ownsDeadline(CallSession session) {
        if (session == null || session.getDeadlineAt() == null) {
            return false;
        }
        CallAttempt attempt = attemptForSession(session);
        if (attempt == null) {
            return false;
        }
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig config =
                resolveExecutionConfig(attempt);
        return config != null && config.campaignType() == CampaignType.MISSED_CALL;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private CallAttempt attemptForSession(CallSession session) {
        if (session.getCallAttemptId() == null) {
            return null;
        }
        return callAttemptRepository.findByIdAndDeletedAtIsNull(session.getCallAttemptId())
                .orElse(null);
    }

    private CampaignRuntimeConfigResolver.CampaignRuntimeConfig resolveExecutionConfig(
            CallAttempt attempt) {
        if (attempt.getExecutionId() == null) {
            return null;
        }
        return executionRepository.findByIdAndDeletedAtIsNull(attempt.getExecutionId())
                .map(runtimeConfigResolver::resolve)
                .orElse(null);
    }

    /**
     * Statuses a call may hold while its budget is still running. Kept as the
     * enum so the in-code guard compares like with like; the SQL sweep uses the
     * same values as bind parameters.
     */
    private static final java.util.Set<CallSessionStatus> LIVE_STATUSES =
            java.util.Set.of(
                    CallSessionStatus.INITIATED,
                    CallSessionStatus.DIALING,
                    CallSessionStatus.RINGING,
                    CallSessionStatus.ANSWERED);

    /** The same statuses as SQL literals, for the sweep queries. */
    private static final List<String> LIVE_STATUS_NAMES = LIVE_STATUSES.stream()
            .map(Enum::name)
            .toList();
}
