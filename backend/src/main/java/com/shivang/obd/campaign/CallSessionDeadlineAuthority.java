package com.shivang.obd.campaign;

import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single authority for a call session's {@code deadline_at} (VB-7B,
 * extracted from {@code PlayfileExecutionService.applyMaxCallDuration}).
 *
 * <h2>Why this was extracted</h2>
 *
 * <p>Before VB-7B, {@code deadline_at} had exactly one writer in the entire
 * platform: a private method on the PLAYFILE execution service. That was fine
 * while one campaign type used it, and it quietly became wrong the moment a
 * second one needed the same mechanism — a MISSED_CALL call is bounded by a ring
 * budget rather than by a playback-length cap, but the mechanism (compute an
 * instant, persist it, let the existing reconciliation path act) is identical.
 * Two private copies of the same calculation would drift.
 *
 * <p>This class is that one authority. {@code PlayfileExecutionService} now
 * delegates here and its behaviour is unchanged; {@code MissedCallExecutionService}
 * uses the same authority for both of its phases.
 *
 * <h2>Anchors</h2>
 *
 * <p>The instant a budget is measured <em>from</em> differs by phase, and getting
 * it wrong is the specific bug the brief calls out: an answered call must not
 * inherit the pre-answer deadline.
 *
 * <ul>
 *   <li>{@link Anchor#SESSION_START} — pre-answer. Measured from the call
 *       session's initiation, so a call that never answers is bounded from the
 *       moment it was placed.</li>
 *   <li>{@link Anchor#ANSWERED} — post-answer. Measured from the answer instant,
 *       which rebases the deadline onto the new phase. Used by PLAYFILE (whose
 *       cap is a property of an established session) and by the post-answer
 *       phase of MISSED_CALL.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 *
 * <p>A session that already carries a deadline is left alone unless a rebase is
 * explicitly requested. That is what makes a duplicate {@code CHANNEL_ANSWER}
 * harmless: the first answer stamps the post-answer deadline and every later one
 * is a no-op, so no second timer and no second terminal transition can be
 * created. PLAYFILE keeps exactly this behaviour, which is why it is expressed
 * as the default rather than as an option.
 */
@Service
@RequiredArgsConstructor
public class CallSessionDeadlineAuthority {

    /** Where a budget is measured from. */
    public enum Anchor {
        /** From the session's initiation — a pre-answer (ringing) budget. */
        SESSION_START,
        /** From the answer instant — a post-answer (connected) budget. */
        ANSWERED
    }

    private final CallSessionRepository callSessionRepository;

    /**
     * Computes the deadline instant for a budget measured from {@code anchor}.
     * Returns {@code null} when there is nothing sensible to anchor to.
     */
    public Instant computeDeadline(CallSession session, Anchor anchor, int seconds) {
        if (session == null || seconds <= 0) {
            return null;
        }
        Instant from = anchor == Anchor.ANSWERED
                ? (session.getAnsweredAt() != null ? session.getAnsweredAt() : Instant.now())
                : session.getInitiatedAt();
        return from == null ? null : from.plusSeconds(seconds);
    }

    /**
     * Stamps {@code deadline_at} on a session, idempotently.
     *
     * @param session the session to stamp (must already carry its anchor)
     * @param anchor  where the budget is measured from
     * @param seconds the budget in seconds
     * @param rebase  when {@code false} (the default) an existing deadline is
     *                never overwritten, so a duplicate answer event cannot move
     *                it; when {@code true} an answered session's deadline is
     *                rebased onto the answer instant
     * @return true when this call recorded the deadline
     */
    @Transactional
    public boolean applyDeadline(CallSession session, Anchor anchor, int seconds,
            boolean rebase) {
        if (session == null) {
            return false;
        }
        if (session.getDeadlineAt() != null && !rebase) {
            return false;
        }
        Instant deadline = computeDeadline(session, anchor, seconds);
        if (deadline == null) {
            return false;
        }
        session.setDeadlineAt(deadline);
        callSessionRepository.save(session);
        return true;
    }

    /** Convenience: apply a budget with the default (non-rebasing) semantics. */
    @Transactional
    public boolean applyDeadline(CallSession session, Anchor anchor, int seconds) {
        return applyDeadline(session, anchor, seconds, false);
    }

    /**
     * Whether a session already carries a deadline, used by the MISSED_CALL
     * policy to tell a pre-answer budget apart from a post-answer one.
     */
    public boolean hasDeadline(CallSession session) {
        return session != null && session.getDeadlineAt() != null;
    }

    /** Loads a live session, or {@code null}. */
    public CallSession findLive(UUID sessionId) {
        return sessionId == null ? null
                : callSessionRepository.findByIdAndDeletedAtIsNull(sessionId).orElse(null);
    }
}
