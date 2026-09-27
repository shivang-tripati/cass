package com.shivang.obd.voice.agent;

/**
 * Machine-readable VB-4A agent-foundation reason codes. Availability
 * results are explainable (never an opaque boolean); endpoint/dialability
 * failures reuse the VB-3 codes so callers need one vocabulary.
 */
public final class AgentFoundationReasons {

    /** ACTIVE + online presence + enabled endpoint + free concurrency slot. */
    public static final String AVAILABLE = "AGENT_AVAILABLE";

    /** Agent administratively suspended — not selectable until reactivated. */
    public static final String AGENT_SUSPENDED = "AGENT_SUSPENDED";

    /** Agent permanently barred (DISABLED is terminal). */
    public static final String AGENT_DISABLED = "AGENT_DISABLED";

    /** Agent presence is OFFLINE. */
    public static final String AGENT_OFFLINE = "AGENT_OFFLINE";

    /** Agent presence is BUSY (owned by the reservation lifecycle). */
    public static final String AGENT_BUSY = AgentReasons.AGENT_BUSY;

    /** No enabled, dialable endpoint exists for the agent. */
    public static final String AGENT_ENDPOINT_INVALID = AgentReasons.AGENT_ENDPOINT_INVALID;

    /** Endpoint supports only types the current telephony setup can dial. */
    public static final String AGENT_ENDPOINT_UNSUPPORTED =
            "AGENT_ENDPOINT_UNSUPPORTED";

    /** Concurrency budget exhausted (live CallLegs = maxConcurrentCalls). */
    public static final String AGENT_AT_CAPACITY = "AGENT_AT_CAPACITY";

    /** Administratively ACTIVE — full availability not implied. */
    public static final String AGENT_ACTIVE = "AGENT_ACTIVE";

    /** Present but not available (e.g. OFFLINE while ACTIVE). */
    public static final String AGENT_PRESENT = "AGENT_PRESENT_NOT_AVAILABLE";

    private AgentFoundationReasons() {
    }
}
