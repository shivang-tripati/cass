package com.shivang.obd.voice.acd;

/**
 * Machine-readable ACD reason codes (VB-4C) — VB-0/VB-3 explainability
 * style. Reuses {@link com.shivang.obd.voice.agent.AgentReasons} for
 * agent-level rejections; this class adds the queue/waiting-call/ACD
 * layer. No opaque scoring, no invented taxonomy.
 */
public final class AcdReasons {

    // === queue eligibility ===
    /** Queue id does not resolve (or belongs to another tenant — fail closed). */
    public static final String QUEUE_NOT_FOUND = "QUEUE_NOT_FOUND";
    /** Queue exists but is INACTIVE/DISABLED — no selection from it. */
    public static final String QUEUE_NOT_ACTIVE = "QUEUE_NOT_ACTIVE";

    // === waiting-call eligibility ===
    /** Waiting-call id does not resolve (or belongs to another tenant). */
    public static final String WAITING_CALL_NOT_FOUND = "WAITING_CALL_NOT_FOUND";
    /** Call is not WAITING (terminal, or foreign queue row). */
    public static final String WAITING_CALL_NOT_WAITING = "WAITING_CALL_NOT_WAITING";

    // === member/selection layer ===
    /** Queue has no ACTIVE memberships at all. */
    public static final String NO_ACTIVE_MEMBERS = "NO_ACTIVE_MEMBERS";
    /** No candidate passed eligibility/reservation (details per candidate). */
    public static final String NO_ELIGIBLE_AGENT = "NO_ELIGIBLE_AGENT";

    // === release reasons (agent_reservations.release_reason vocabulary) ===
    /** ACD hold expired before the consuming (VB-4D) flow claimed it. */
    public static final String EXPIRED = "ACD_EXPIRED";
    /** Assignment claim lost a race — the freshly made hold is unwound. */
    public static final String ASSIGNMENT_LOST = "ASSIGNMENT_LOST";

    private AcdReasons() {
    }
}
