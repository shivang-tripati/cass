package com.shivang.obd.voice.agent;

/**
 * Agent reservation lifecycle (VB-3): RESERVED → ACTIVE (agent leg
 * answered/bridging) → RELEASED. Atomic transitions use conditional
 * UPDATEs; a reservation is terminal at RELEASED and can never be reused.
 */
public enum AgentReservationStatus {
    /** Atomically claimed — counts against max_concurrent_calls. */
    RESERVED,

    /** Agent leg answered — reservation confirmed for the live call. */
    ACTIVE,

    /** Terminal — no longer counts against agent concurrency. */
    RELEASED
}
