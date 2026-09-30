package com.shivang.obd.campaign;

import com.shivang.obd.telephony.EslEvent;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.media.VoiceMediaController;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-6E stale-call reconciler: enforces the maximum call duration and recovers
 * calls whose outcome was never reported.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The VB-6E audit found that a call attempt set {@code IN_PROGRESS} before
 * its dial, and that the <em>only</em> automated route to a terminal state was
 * the FreeSWITCH {@code CHANNEL_HANGUP} event. So if that event was lost — a
 * process crash, a dropped ESL connection, a provider that never reported —
 * the attempt stayed {@code IN_PROGRESS} forever: never re-dialed (the retry
 * pass only looks at {@code FAILED}), never completed, and its execution never
 * left {@code RUNNING} because reconciliation requires all attempts terminal.
 * The subscriber simply never got a result.
 *
 * <p>The repository already contains six reconcilers for other domains
 * (capacity, agent reservations, agent-connect timeout, DTMF timeout, ACD
 * maintenance, ACD retry). Campaign attempts were the one participant with
 * none. This follows that same convention.
 *
 * <h2>What it sweeps</h2>
 *
 * <p>Two distinct stale shapes, deliberately separated because they mean
 * different things:
 *
 * <ol>
 *   <li><b>Overdue established call</b> — a session that was answered and has
 *       a {@code deadline_at} in the past. This is the maximum-call-duration
 *       enforcement. The session's failure code is set to
 *       {@link CallFailureCode#MAX_DURATION_EXCEEDED} <em>before</em> the channel
 *       is asked to terminate, so the resulting {@code CHANNEL_HANGUP} picks
 *       the failure up through the existing
 *       {@code session.getFailureCode()} precedence. That is why a timeout can
 *       never be misread as a normal release — the normal-clearing cause our
 *       own {@code uuid_kill} produces is exactly the cause that otherwise means
 *       "delivered blast".</li>
 *   <li><b>Overdue unanswered call</b> — an attempt dispatched but whose session
 *       has neither an answer nor a terminal state within the stale window. The
 *       provider may or may not have connected the subscriber, which is
 *       precisely the case a campaign-configured retry exists for, so it is
 *       recorded as {@link CallFailureCode#STALE_ATTEMPT_RECONCILED} and left
 *       to the campaign's own policy rather than being silently dropped.</li>
 * </ol>
 *
 * <h2>Guarantees</h2>
 *
 * <ul>
 *   <li><b>Idempotent.</b> A session is only acted on while it is still
 *       non-terminal, and the failure code is written before any external
 *       call, so a second sweep finds nothing to do. A duplicate or late ESL
 *       event that arrives afterwards is already handled by
 *       {@code EslEventService}'s terminal-attempt guard.</li>
 *   <li><b>Never resurrects a completed call.</b> Only sessions that are not
 *       already terminal are considered, and the attempt status guard in
 *       {@code EslEventService} still applies afterwards.</li>
 *   <li><b>No double counting.</b> The VB-6C hold is released and the VB-6D.3
 *       attempt is <em>not</em> re-consumed. Retries are created only by the
 *       normal retry pass, which is the single place that decides whether a
 *       retry exists, so this sweeper can never produce a duplicate.</li>
 *   <li><b>Never invents a pre-dispatch failure.</b> Both codes are dispatched
 *       outcomes, so they stay outside the pre-dispatch set and are classified
 *       by the campaign's real rules.</li>
 *   <li><b>Tenant-safe.</b> Every query is tenant-bounded, and a session is
 *       never terminated using another tenant's routing decision.</li>
 * </ul>
 *
 * <p>Runs on the <em>existing</em> {@code CampaignExecutionOrchestrator} tick
 * as its final step. No new scheduler, no new framework, no distributed lock.
 */
@Service
@Slf4j
public class StaleCallReconciler {

    /**
     * How long an attempt may sit in a non-terminal state before the platform
     * stops waiting for a provider event that is never coming.
     *
     * <p>Chosen to sit safely above every provider-side timeout the platform
     * can already be waiting on: ESL command timeout is 30s, connect timeout
     * 10s, and a normal ring cycle is tens of seconds. A value comfortably
     * above all of them means the sweeper never races a call that is merely
     * slow. 5 minutes matches the existing capacity-reservation stale window,
     * so both reconcilers become effective on the same horizon.
     */
    static final Duration STALE_ATTEMPT_THRESHOLD = Duration.ofMinutes(5);

    /**
     * Session states that mean the call is still live and can be timed out or
     * reclaimed. A terminal session is never touched.
     */
    private static final Set<CallSessionStatus> LIVE_SESSION_STATUSES = Set.of(
            CallSessionStatus.INITIATED,
            CallSessionStatus.DIALING,
            CallSessionStatus.RINGING,
            CallSessionStatus.ANSWERED,
            CallSessionStatus.PLAYING,
            CallSessionStatus.PLAYBACK_COMPLETED);

    private final CallSessionRepository callSessionRepository;
    private final CallAttemptRepository attemptRepository;
    private final VoiceMediaController mediaController;
    private final EntityManager entityManager;
    /**
     * VB-7B: policies that own a session's deadline themselves. Optional - a
     * {@code null} provider means nothing else competes for this column and this
     * sweeper behaves exactly as it did before VB-7B.
     */
    private final org.springframework.beans.factory.ObjectProvider<CallSessionDeadlineOwner>
            deadlineOwners;

    /** The pre-VB-7B constructor, retained so every existing construction site is unchanged. */
    public StaleCallReconciler(
            CallSessionRepository callSessionRepository,
            CallAttemptRepository attemptRepository,
            VoiceMediaController mediaController,
            EntityManager entityManager) {
        this(callSessionRepository, attemptRepository, mediaController, entityManager, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StaleCallReconciler(
            CallSessionRepository callSessionRepository,
            CallAttemptRepository attemptRepository,
            VoiceMediaController mediaController,
            EntityManager entityManager,
            org.springframework.beans.factory.ObjectProvider<CallSessionDeadlineOwner>
                    deadlineOwners) {
        this.callSessionRepository = callSessionRepository;
        this.attemptRepository = attemptRepository;
        this.mediaController = mediaController;
        this.entityManager = entityManager;
        this.deadlineOwners = deadlineOwners;
    }

    /**
     * One reconciliation pass. Invoked by the existing orchestrator tick.
     *
     * <p>Read-only by design: the pass <em>finds</em> candidates, then each is
     * finalised in its own transaction via
     * {@link #finalizeStaleSession}. That keeps a slow or failing provider
     * teardown from holding the sweep's transaction open, and means one bad
     * session cannot block the rest.
     */
    public void reconcile() {
        Instant now = Instant.now();
        int overdue = terminateOverdueCalls(now);
        int stranded = finalizeStrandedAttempts(now);
        // VB-8E: attempts that were claimed but never produced a session. Before
        // VB-8D these could not exist (the attempt only became IN_PROGRESS inside
        // the same transaction that created the session); the claim is now its
        // own committed transaction, so a crash between the two leaves an
        // attempt IN_PROGRESS with no session at all - invisible to the two
        // sweeps above and therefore stuck forever.
        int orphaned = finalizeOrphanedClaims(now);
        if (overdue > 0 || stranded > 0 || orphaned > 0) {
            log.warn("VB-6E stale-call reconciliation: {} overdue call(s) terminated, "
                    + "{} stranded attempt(s) finalized, {} orphaned claim(s) settled",
                    overdue, stranded, orphaned);
        }
    }

    /**
     * Maximum call duration: terminate every answered session past its
     * persisted deadline.
     */
    private int terminateOverdueCalls(Instant now) {
        List<UUID> overdue = liveSessionsPastDeadline(now);
        int handled = 0;
        for (UUID sessionId : overdue) {
            if (finalizeOverdueSession(sessionId)) {
                handled++;
            }
        }
        return handled;
    }

    /**
     * VB-8E recovery: settle attempts that were claimed but never produced a
     * call session.
     *
     * <p>Since VB-8D the dispatch claim is its own committed transaction, so an
     * attempt moves {@code QUEUED -> IN_PROGRESS} <em>before</em> the transaction
     * that creates its {@code CallSession}. A crash in that window leaves an
     * attempt that is {@code IN_PROGRESS} with no session row - which the
     * stranded-session sweep above, which is driven by {@code call_sessions},
     * can never see. Such an attempt is also not {@code QUEUED}, so the dial
     * step will not pick it up again (correctly, to prevent a duplicate call),
     * and {@code reconcileExecution} will never settle the execution because one
     * of its attempts is not terminal. The attempt, and the execution with it,
     * would otherwise wedge permanently.
     */
    private int finalizeOrphanedClaims(Instant now) {
        Instant cutoff = now.minus(STALE_ATTEMPT_THRESHOLD);
        List<UUID> orphans = orphanedClaimedAttempts(cutoff);
        int handled = 0;
        for (UUID attemptId : orphans) {
            if (finalizeOrphanedClaim(attemptId)) {
                handled++;
            }
        }
        return handled;
    }

    /**
     * Stale {@code IN_PROGRESS} attempts that have no session at all.
     *
     * <p>Deliberately a {@code NOT EXISTS} on the session rather than a join, so
     * the population can never overlap the stranded-session sweep: an attempt
     * with a session is that sweep's business, one without is this one's.
     *
     * <p>Bounded and ordered exactly like the existing sweeps, and re-checks
     * staleness inside the finalising transaction so a long pass cannot settle an
     * attempt that has just been claimed.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    @SuppressWarnings("unchecked")
    List<UUID> orphanedClaimedAttempts(Instant cutoff) {
        return entityManager.createNativeQuery("""
                SELECT a.id FROM call_attempts a
                WHERE a.deleted_at IS NULL
                  AND a.status = 'IN_PROGRESS'
                  AND a.started_at IS NOT NULL
                  AND a.started_at <= :cutoff
                  AND NOT EXISTS (
                      SELECT 1 FROM call_sessions s
                      WHERE s.call_attempt_id = a.id
                        AND s.deleted_at IS NULL)
                ORDER BY a.started_at
                LIMIT :batchSize
                """)
                .setParameter("cutoff", cutoff)
                .setParameter("batchSize", MAX_BATCH)
                .getResultList();
    }

    /**
     * VB-8E: settles one claimed-but-unsessioned attempt.
     *
     * <h2>Why CANCELLED and not FAILED</h2>
     *
     * <p>This attempt's external outcome is genuinely <b>unknown</b>. It is
     * equally consistent with "the JVM died before the dialer was called" and
     * with "FreeSWITCH accepted the call and the JVM died before the provider
     * call id could be persisted". PostgreSQL and FreeSWITCH are not one atomic
     * transaction and this method does not pretend otherwise.
     *
     * <p>So it is recorded as {@code CANCELLED}, which is terminal and which the
     * retry step never selects. Turning an unknown external outcome into
     * {@code FAILED} would hand it to {@link RetryPolicyService}, and a retry
     * would place a <em>second real call</em> to a contact who may already be
     * receiving the first. Under-dialing is the recoverable direction;
     * double-dialing is not.
     *
     * <p>What is deliberately <em>not</em> done: the attempt is not requeued,
     * because a requeue would dispatch it again for the same reason. What this
     * method restores is convergence - the attempt is settled, so
     * {@code reconcileExecution} can finish the execution instead of waiting
     * forever.
     *
     * <p>If a call really was placed, it remains live in FreeSWITCH and ends on
     * its own timeout; the platform simply does not learn the outcome. The
     * channel cannot be correlated afterwards because {@code providerCallId} was
     * never persisted. Closing that accounting gap needs an ESL probe for the
     * deterministic {@code origination_uuid} (which equals this attempt's id) and
     * belongs in its own phase, not here.
     *
     * <p>No daily safety is consumed or released: the VB-6C reservation and the
     * VB-6D.3 attempt both live in the dispatch transaction, which rolled back
     * with the crash, so there is nothing to give back.
     */
    @Transactional
    public boolean finalizeOrphanedClaim(UUID attemptId) {
        var attempt = attemptRepository.findByIdAndDeletedAtIsNull(attemptId).orElse(null);
        if (attempt == null
                || attempt.getStatus() != CallAttemptStatus.IN_PROGRESS
                || attempt.getStartedAt() == null
                || attempt.getStartedAt().isAfter(
                        Instant.now().minus(STALE_ATTEMPT_THRESHOLD))) {
            return false;
        }
        // Re-checked here, in the finalising transaction: a session appearing
        // between the read pass and this write means the dispatch transaction
        // did complete after all, and that attempt is the stranded-session
        // sweep's business, not ours.
        if (!callSessionRepository
                .findByCallAttemptIdAndDeletedAtIsNull(attemptId).isEmpty()) {
            return false;
        }

        attempt.setStatus(CallAttemptStatus.CANCELLED);
        attempt.setFailureCode("CLAIMED_NOT_DISPATCHED");
        attempt.setFailureReason("Attempt was claimed but produced no call session within "
                + STALE_ATTEMPT_THRESHOLD.toSeconds()
                + "s; external outcome unknown, so it is not retried");
        attempt.setCompletedAt(Instant.now());
        attemptRepository.save(attempt);

        log.info("Settled orphaned claim {} (execution {}, contact {}) - claimed but no session; "
                + "external outcome unknown, not retried",
                attemptId, attempt.getExecutionId(), attempt.getContactId());
        return true;
    }

    /**
     * Recovery: fail attempts dispatched long ago whose provider outcome was
     * never reported.
     */
    private int finalizeStrandedAttempts(Instant now) {
        Instant cutoff = now.minus(STALE_ATTEMPT_THRESHOLD);
        List<UUID> stranded = strandedAttemptSessions(cutoff);
        int handled = 0;
        for (UUID sessionId : stranded) {
            if (finalizeStrandedSession(sessionId)) {
                handled++;
            }
        }
        return handled;
    }

    // =====================================================================
    // Candidate queries (read-only)
    // =====================================================================

    /**
     * Answered sessions whose deadline has passed. Uses the partial index
     * added in V52, so this is a bounded range scan rather than a full table
     * sweep. Excludes terminal sessions.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    @SuppressWarnings("unchecked")
    List<UUID> liveSessionsPastDeadline(Instant now) {
        return entityManager.createNativeQuery("""
                SELECT s.id FROM call_sessions s
                WHERE s.deleted_at IS NULL
                  AND s.deadline_at IS NOT NULL
                  AND s.deadline_at <= :now
                  AND s.status IN ('INITIATED','DIALING','RINGING','ANSWERED',
                                   'PLAYING','PLAYBACK_COMPLETED')
                ORDER BY s.deadline_at
                LIMIT :batchSize
                """)
                .setParameter("now", now)
                .setParameter("batchSize", MAX_BATCH)
                .getResultList();
    }

    /**
     * Non-terminal sessions of attempts that have been dispatched longer ago
     * than the stale threshold and never reached a terminal state — the shape
     * left behind by a lost hangup event.
     *
     * <p>VB-8E: this was {@code SELECT DISTINCT s.id ... ORDER BY a.started_at},
     * which PostgreSQL rejects outright — for {@code DISTINCT} every ordering
     * expression must appear in the select list, and {@code a.started_at} did
     * not. The failure was silent in the only sense that it had never been
     * observed: the sweep had no test that put a stranded session in front of
     * it, so the entire stale-attempt recovery path threw on its first real
     * match, and because the exception escaped {@link #reconcile()} it also
     * skipped every later sweep in the same pass. One row of dead recovery
     * logic that had never run.
     *
     * <p>{@code GROUP BY s.id} expresses the same intent legally: a session
     * joins to at most one attempt, so grouping by the primary key yields
     * exactly the rows {@code DISTINCT} was asking for, and the aggregate in
     * the ordering keeps the oldest-dispatch-first behaviour the batch limit
     * depends on.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    @SuppressWarnings("unchecked")
    List<UUID> strandedAttemptSessions(Instant cutoff) {
        return entityManager.createNativeQuery("""
                SELECT s.id FROM call_sessions s
                JOIN call_attempts a ON a.id = s.call_attempt_id
                WHERE s.deleted_at IS NULL
                  AND a.deleted_at IS NULL
                  AND a.tenant_id = s.tenant_id
                  AND a.status = 'IN_PROGRESS'
                  AND a.started_at IS NOT NULL
                  AND a.started_at <= :cutoff
                  AND s.status IN ('INITIATED','DIALING','RINGING','ANSWERED',
                                   'PLAYING','PLAYBACK_COMPLETED')
                GROUP BY s.id
                ORDER BY min(a.started_at)
                LIMIT :batchSize
                """)
                .setParameter("cutoff", cutoff)
                .setParameter("batchSize", MAX_BATCH)
                .getResultList();
    }

    /** Bounded per pass, matching the existing reconcilers' batch convention. */
    static final int MAX_BATCH = 200;

    // =====================================================================
    // Finalisation (one transaction each)
    // =====================================================================

    /**
     * Applies the maximum call duration to one session.
     *
     * <p>Order matters and is the whole design: record the failure on the
     * session <em>first</em>, then ask the media boundary to hang the channel
     * up. The resulting {@code CHANNEL_HANGUP} reads
     * {@code session.getFailureCode()} and therefore fails the attempt with
     * {@code MAX_DURATION_EXCEEDED} rather than treating our own normal-clearing
     * hangup as a completed blast. The transaction commits before the channel is
     * touched, so a provider teardown failure cannot lose the classification.
     */
    @Transactional
    public boolean finalizeOverdueSession(UUID sessionId) {
        CallSession session = callSessionRepository
                .findByIdAndDeletedAtIsNull(sessionId)
                .orElse(null);
        if (session == null || !LIVE_SESSION_STATUSES.contains(session.getStatus())
                || session.getDeadlineAt() == null
                || session.getDeadlineAt().isAfter(Instant.now())) {
            return false; // already handled, or not actually overdue
        }
        // VB-7B: a session may carry a deadline that is a campaign MISSION
        // budget rather than the maximum-call-duration cap this sweeper owns
        // (MISSED_CALL: reaching the deadline IS the success, so this sweeper
        // must not record a failure code over it). Declining is the whole
        // interaction - the owning policy terminates it without recording a
        // failure, which is what makes the hangup classify as COMPLETED.
        if (deadlineOwners != null) {
            CallSessionDeadlineOwner owner = deadlineOwners.getIfAvailable();
            if (owner != null && owner.ownsDeadline(session)) {
                log.debug("Session {} deadline is owned by a campaign policy - "
                        + "leaving it to that policy", sessionId);
                return false;
            }
        }
        // Idempotency: a failure code already on the session means teardown was
        // already initiated for this call, by this sweeper or by any other
        // terminal path. Acting again would issue a second uuid_kill and, worse,
        // would overwrite a more specific recorded reason.
        if (session.getFailureCode() != null && !session.getFailureCode().isBlank()) {
            return false;
        }

        session.setFailureCode(CallFailureCode.MAX_DURATION_EXCEEDED.name());
        session.setFailureReason("Call exceeded its maximum duration (deadline "
                + session.getDeadlineAt() + ")");
        callSessionRepository.save(session);

        log.info("Terminating call {} - maximum call duration exceeded (deadline {})",
                sessionId, session.getDeadlineAt());

        // Best effort, outside our transaction's guarantee. If the channel is
        // already gone, the recorded failure code is what matters: the attempt
        // is finalised by the reconciliation of the attempt itself, and a later
        // or duplicate hangup event is a no-op.
        try {
            mediaController.terminateCall(session.getId(), session.getProviderCallId());
        } catch (RuntimeException e) {
            log.warn("Provider teardown after max-duration for call {} failed: {}",
                    sessionId, e.getMessage());
        }
        return true;
    }

    /**
     * Finalises one attempt whose provider outcome was never reported.
     *
     * <p>Recorded as {@code STALE_ATTEMPT_RECONCILED} — a dispatched outcome, so
     * the campaign's own retry policy decides what happens next, exactly as it
     * would for any other dispatched failure. The VB-6C hold is released rather
     * than confirmed, because the platform never observed a provider
     * acceptance.
     */
    @Transactional
    public boolean finalizeStrandedSession(UUID sessionId) {
        CallSession session = callSessionRepository
                .findByIdAndDeletedAtIsNull(sessionId)
                .orElse(null);
        if (session == null || !LIVE_SESSION_STATUSES.contains(session.getStatus())) {
            return false;
        }
        UUID attemptId = session.getCallAttemptId();
        if (attemptId == null) {
            return false;
        }
        var attempt = attemptRepository.findByIdAndDeletedAtIsNull(attemptId).orElse(null);
        if (attempt == null
                || attempt.getStatus() != CallAttemptStatus.IN_PROGRESS
                || attempt.getStartedAt() == null
                || attempt.getStartedAt().isAfter(Instant.now().minus(STALE_ATTEMPT_THRESHOLD))) {
            return false;
        }

        String reason = "No provider outcome was reported within "
                + STALE_ATTEMPT_THRESHOLD.toSeconds() + "s of dispatch";
        session.setFailureCode(CallFailureCode.STALE_ATTEMPT_RECONCILED.name());
        session.setFailureReason(reason);
        session.setEndedAt(Instant.now());
        session.setStatus(CallSessionStatus.FAILED);
        callSessionRepository.save(session);

        // Mirrors the ESL hangup handler so the persisted outcome is identical
        // whichever path discovered the call was over.
        attempt.setStatus(CallAttemptStatus.FAILED);
        attempt.setFailureCode(CallFailureCode.STALE_ATTEMPT_RECONCILED.name());
        attempt.setFailureReason(reason);
        attempt.setCompletedAt(Instant.now());
        attemptRepository.save(attempt);

        log.info("Reconciled stranded attempt {} (call {}) - {}", attemptId, sessionId, reason);
        return true;
    }
}
