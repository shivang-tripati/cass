package com.shivang.obd.voice.agent;

/**
 * The single authority for the agent ring window (VB-7A).
 *
 * <p><b>Why this is a type and not a constant inside the scheduler.</b> Before
 * VB-7A the ring window existed only as
 * {@code AgentConnectTimeoutScheduler.CONNECT_TIMEOUT_SECONDS}. A
 * CONNECT_BY_AGENT campaign now carries its own ring budget in its frozen
 * execution snapshot, so the window is a <em>bounded, validated</em> value that
 * two modules must agree on: the campaign configuration validator that accepts
 * or rejects a stored value, and the scheduler that enforces it. If each held
 * its own copy of "the minimum" or "the maximum", a value could be accepted at
 * write time and then be unrepresentable at enforcement time. One authority,
 * two readers.
 *
 * <h2>How the bounds are derived</h2>
 * <ul>
 *   <li>{@link #DEFAULT_RING_SECONDS} is the pre-VB-7A platform constant
 *       ({@code CONNECT_TIMEOUT_SECONDS}), unchanged. Every existing non-VB-7A
 *       path — notably a DTMF/IVR campaign whose terminal action is
 *       {@code CONNECT_BY_AGENT} and which therefore has no CONNECT_BY_AGENT
 *       type config at all — keeps exactly its previous behaviour.</li>
 *   <li>{@link #MIN_RING_SECONDS} is a floor, not a preference: a window shorter
 *       than this cannot express "ring the agent", and would only convert a
 *       legitimate slow pickup into {@code AGENT_NO_ANSWER}.</li>
 *   <li>{@link #MAX_RING_SECONDS} is bounded by {@code AcdService#RESERVATION_TTL}
 *       (5 minutes), the ACD hold's own TTL. The ring deadline must always
 *       arrive <em>before</em> the ACD hold can expire, otherwise
 *       {@code AcdMaintenanceScheduler} would release the hold underneath a
 *       ring that is still legitimately in progress and a second timeout
 *       authority would exist. A maximum strictly below the TTL makes
 *       {@link AgentConnectTimeoutScheduler} the sole enforcer of the outbound
 *       ring window.</li>
 * </ul>
 *
 * <p>It is a deliberate non-goal to make this window interact with
 * {@code Queue.maxWaitSeconds}: that field bounds how long a call <em>waits in a
 * queue</em> (VB-4B inbound), whereas this bounds how long an already-answered
 * outbound call rings one already-reserved agent.
 */
public final class AgentRingWindow {

    /** Platform default ring window in seconds (pre-VB-7A constant). */
    public static final int DEFAULT_RING_SECONDS = 60;

    /** Shortest configurable ring window, in seconds. */
    public static final int MIN_RING_SECONDS = 10;

    /** Longest configurable ring window, in seconds (must stay under the ACD hold TTL). */
    public static final int MAX_RING_SECONDS = 240;

    private AgentRingWindow() {
    }

    /** Whether a stored value is inside the supported range. */
    public static boolean isValid(Integer seconds) {
        return seconds != null
                && seconds >= MIN_RING_SECONDS
                && seconds <= MAX_RING_SECONDS;
    }

    /**
     * Resolves an effective ring window: a valid configured value is honoured
     * verbatim, anything else (absent, out of range, unparseable) falls back to
     * the platform default rather than failing a call in progress. A corrupted
     * stored value must degrade to the previously-enforced constant, never to
     * an instant {@code AGENT_NO_ANSWER}.
     */
    public static int effectiveSeconds(Integer configuredSeconds) {
        return isValid(configuredSeconds) ? configuredSeconds : DEFAULT_RING_SECONDS;
    }

    /** Whether a leg initiated at {@code initiatedAt} is past its ring deadline. */
    public static boolean isExpired(java.time.Instant initiatedAt, int ringSeconds) {
        return initiatedAt != null
                && !initiatedAt.plusSeconds(ringSeconds).isAfter(java.time.Instant.now());
    }

    /** Human-readable range, used in configuration error messages and API docs. */
    public static String describeRange() {
        return MIN_RING_SECONDS + "-" + MAX_RING_SECONDS;
    }
}
