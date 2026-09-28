package com.shivang.obd.campaign.config;

/**
 * The single authority for the MISSED_CALL time budget (VB-7B).
 *
 * <p><b>Why this is a type and not a constant inside a service.</b> The ring
 * duration is a bounded, validated value that two places must agree on: the
 * configuration parser that accepts or rejects a stored value, and the runtime
 * that enforces it. If each held its own copy of "the minimum" or "the maximum",
 * a value could be accepted at write time and then be unrepresentable at
 * enforcement time. One authority, two readers — the same shape as
 * {@code MaxCallDurationPolicy} (VB-6E) and {@code AgentRingWindow} (VB-7A).
 *
 * <h2>What the duration actually controls</h2>
 *
 * <p>One number, two phases, per the locked OD-5 decision:
 *
 * <ul>
 *   <li><b>Pre-answer</b> — the maximum time the callee may ring. When the budget
 *       elapses the platform terminates the call itself, which produces a
 *       NORMAL_CLEARING (cause 16) hangup that the existing classifier records as
 *       {@code COMPLETED} with no failure code and therefore no retry. This is the
 *       successful MISSED_CALL delivery (locked OD-1).</li>
 *   <li><b>Post-answer</b> — the maximum time the connected session may live. The
 *       deadline is <em>rebased</em> onto the answer instant, so an answered call
 *       never inherits the pre-answer deadline (locked OD-2). It is terminated at
 *       that deadline the same way, so it also completes rather than sitting until
 *       stale reconciliation (locked OD-2).</li>
 * </ul>
 *
 * <h2>How the bounds are derived</h2>
 *
 * <ul>
 *   <li>{@link #MIN_RING_SECONDS} = 10 s. A window shorter than this cannot
 *       express "ring the subscriber"; it would convert a legitimate slow pickup
 *       into an instant teardown.</li>
 *   <li>{@link #MAX_RING_SECONDS} = 60 s. Derived from the platform's existing
 *       ring scale — {@code AgentConnectTimeoutScheduler.CONNECT_TIMEOUT_SECONDS}
 *       is 60 s for the agent leg — and, more importantly, bounded so the deadline
 *       always arrives far ahead of {@code StaleCallReconciler}'s
 *       {@code STALE_ATTEMPT_THRESHOLD} (5 minutes). A budget above the stale
 *       threshold would let the recovery sweep, not the MISSED_CALL policy,
 *       decide the outcome, and the reconciler records a <em>failure</em>, which
 *       is the opposite of this campaign type's success semantics.</li>
 *   <li>{@link #DEFAULT_RING_SECONDS} = 30 s. Used only where the repository's
 *       existing configuration semantics already permit a default; a stored
 *       MISSED_CALL configuration always carries an explicit value.</li>
 * </ul>
 *
 * <p>Deliberately independent of {@code Queue.maxWaitSeconds} (how long a call
 * waits in an inbound queue) and of {@code MaxCallDurationPolicy} (the maximum
 * lifetime of an <em>established</em> call session). Neither can express a
 * mission whose completion is a ring.
 */
public final class MissedCallRingWindow {

    /** Platform default ring window in seconds. */
    public static final int DEFAULT_RING_SECONDS = 30;

    /** Shortest configurable ring window, in seconds. */
    public static final int MIN_RING_SECONDS = 10;

    /** Longest configurable ring window, in seconds. */
    public static final int MAX_RING_SECONDS = 60;

    private MissedCallRingWindow() {
    }

    /** Whether a stored value is inside the supported range. */
    public static boolean isValid(Integer seconds) {
        return seconds != null
                && seconds >= MIN_RING_SECONDS
                && seconds <= MAX_RING_SECONDS;
    }

    /**
     * Resolves an effective ring window: a valid configured value is honoured
     * verbatim, anything else (absent, out of range) falls back to the platform
     * default rather than failing a call in progress. A corrupted stored value
     * must degrade to the previously-enforced constant, never to an instant
     * teardown.
     */
    public static int effectiveSeconds(Integer configuredSeconds) {
        return isValid(configuredSeconds) ? configuredSeconds : DEFAULT_RING_SECONDS;
    }

    /** Whether an instant has reached the given window's end. */
    public static boolean isExpired(java.time.Instant from, int ringSeconds) {
        return from != null
                && !from.plusSeconds(ringSeconds).isAfter(java.time.Instant.now());
    }

    /** Human-readable range, used in configuration error messages and API docs. */
    public static String describeRange() {
        return MIN_RING_SECONDS + "-" + MAX_RING_SECONDS;
    }
}
