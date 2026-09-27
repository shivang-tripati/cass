package com.shivang.obd.voice.call;

/**
 * Individual call leg lifecycle status.
 */
public enum CallLegStatus {
    INITIATED,
    DIALING,
    RINGING,
    ANSWERED,

    /** Leg is part of an established bridge (VB-3 CONNECT_BY_AGENT). */
    BRIDGED,

    COMPLETED,
    FAILED,
    CANCELLED
}