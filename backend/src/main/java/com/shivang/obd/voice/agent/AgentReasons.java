package com.shivang.obd.voice.agent;

/**
 * Machine-readable agent selection/rejection reason codes (VB-3) —
 * explainability in the VB-0 routing style.
 */
public final class AgentReasons {

    /** An eligible agent was selected. */
    public static final String SELECTED = "AGENT_SELECTED";

    /** Tenant has no agents configured. */
    public static final String AGENT_UNAVAILABLE = "AGENT_UNAVAILABLE";

    /** Tenant agents exist but none is ACTIVE/AVAILABLE with a usable endpoint. */
    public static final String AGENT_NOT_AVAILABLE = "AGENT_NOT_AVAILABLE";

    /** All eligible agents are at their concurrency limit. */
    public static final String AGENT_BUSY = "AGENT_BUSY";

    /** Selected agent has no enabled, dialable endpoint. */
    public static final String AGENT_ENDPOINT_INVALID = "AGENT_ENDPOINT_INVALID";

    /** Cross-tenant agent reference — fail closed. */
    public static final String AGENT_TENANT_MISMATCH = "AGENT_TENANT_MISMATCH";

    /** Campaign/DTMF configuration requests an agent that cannot be used. */
    public static final String AGENT_CONFIG_INVALID = "AGENT_CONFIG_INVALID";

    /** Reservation was claimed by a concurrent caller (lost race). */
    public static final String AGENT_RESERVATION_LOST = "AGENT_RESERVATION_LOST";

    /** Agent-leg originate failed after a successful reservation. */
    public static final String AGENT_ORIGINATE_FAILED = "AGENT_ORIGINATE_FAILED";

    private AgentReasons() {
    }
}
