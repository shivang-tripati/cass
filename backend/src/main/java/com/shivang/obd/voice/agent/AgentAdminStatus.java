package com.shivang.obd.voice.agent;

/**
 * Administrative status of an agent — "is this agent allowed to receive
 * CONNECT_BY_AGENT calls at all?" (VB-3).
 * <p>
 * Deliberately separate from {@link AgentAvailability}, which answers
 * "can this agent take a call right now?". Conflating the two would make
 * an operational pause (BUSY) indistinguishable from a business decision
 * (SUSPENDED).
 */
public enum AgentAdminStatus {
    /** Allowed to receive calls. */
    ACTIVE,

    /** Administratively paused — not selectable until re-activated. */
    SUSPENDED,

    /** Permanently barred from receiving calls. */
    DISABLED
}
