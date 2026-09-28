package com.shivang.obd.campaign;

import com.shivang.obd.voice.call.CallSession;

/**
 * Declares that a call session's persisted {@code deadline_at} is governed by a
 * campaign-specific mission budget rather than by the platform's
 * maximum-call-duration cap (VB-7B).
 *
 * <h2>Why the reconciler has to ask</h2>
 *
 * <p>{@code StaleCallReconciler} enforces a <em>cap</em>: a call that has been
 * established longer than its maximum duration is a fault, so it records
 * {@code MAX_DURATION_EXCEEDED} on the session before terminating. That failure
 * code is what makes the attempt {@code FAILED} and therefore retryable.
 *
 * <p>A MISSED_CALL session also carries a {@code deadline_at}, but its deadline
 * is a <em>mission budget</em>: the call rang for its configured window, which is
 * precisely the successful outcome. Terminating it must therefore record
 * <b>nothing</b>, so the resulting normal-clearing hangup is classified
 * {@code COMPLETED} by the existing {@code EslEventService} precedence and is
 * never retried.
 *
 * <p>Without this seam the two policies would race over the same column, and
 * whichever ran second would decide the outcome — the reconciler would turn a
 * successful ring into a retryable failure. Rather than let a shared
 * reconciliation sweep silently make campaign-type decisions, it asks this one
 * question and declines.
 *
 * <p>This is deliberately the <em>opposite</em> of turning the reconciler into
 * the MISSED_CALL business policy: the policy stays in the campaign module, the
 * reconciler keeps owning maximum duration, and the only shared knowledge is
 * "this deadline is not mine".
 */
public interface CallSessionDeadlineOwner {

    /**
     * Whether this session's deadline is governed by the implementing policy.
     * Implementations must be side-effect free and must not mutate the session.
     */
    boolean ownsDeadline(CallSession session);
}
