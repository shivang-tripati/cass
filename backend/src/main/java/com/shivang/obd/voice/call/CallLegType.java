package com.shivang.obd.voice.call;

/**
 * Type of call leg.
 */
public enum CallLegType {
    /** The customer/callee leg (primary leg for outbound). */
    CUSTOMER,

    /** Agent leg (for CONNECT_BY_AGENT). */
    AGENT,

    /** External forwarding leg (mobile, etc.). */
    EXTERNAL,

    /** AI agent leg (future). */
    AI,

    /** Queue leg (waiting in queue). */
    QUEUE
}