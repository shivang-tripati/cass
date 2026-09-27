package com.shivang.obd.voice.agent;

/**
 * Runtime availability of an agent — "can this agent take a call right
 * now?" (VB-3). Separate from {@link AgentAdminStatus}.
 */
public enum AgentAvailability {
    /** Agent endpoint is registered/idle and eligible for selection. */
    AVAILABLE,

    /** Agent is engaged (active reservation) — not selectable. */
    BUSY,

    /** Agent endpoint is not reachable — not selectable. */
    OFFLINE
}
