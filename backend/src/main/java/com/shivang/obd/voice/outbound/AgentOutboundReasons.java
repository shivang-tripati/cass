package com.shivang.obd.voice.outbound;

/**
 * Machine-readable VB-4E reason codes. Deliberately minimal: agent-side
 * reasons reuse the VB-3/VB-4A vocabulary ({@code AgentReasons},
 * {@code AgentFoundationReasons}) and routing reasons reuse VB-0 codes —
 * only the two conditions with no existing representation are defined
 * here.
 */
public final class AgentOutboundReasons {

    /** Destination missing, blank, or not a structurally valid E.164 number. */
    public static final String INVALID_DESTINATION = "INVALID_DESTINATION";

    /** Routing returned no eligible gateway (routing profile/capacity/policy). */
    public static final String NO_ELIGIBLE_GATEWAY = "NO_ELIGIBLE_GATEWAY";

    /** Gateway channel/CPS capacity reservation failed. */
    public static final String GATEWAY_CAPACITY_EXHAUSTED = "GATEWAY_CAPACITY_EXHAUSTED";

    /** The customer-leg originate failed synchronously. */
    public static final String CALL_ORIGINATE_FAILED = "CALL_ORIGINATE_FAILED";

    private AgentOutboundReasons() {
    }
}
