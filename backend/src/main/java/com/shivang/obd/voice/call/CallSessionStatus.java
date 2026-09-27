package com.shivang.obd.voice.call;

/**
 * Universal call session lifecycle status.
 * <p>
 * This is distinct from CallAttemptStatus (campaign-specific execution state).
 * CallAttemptStatus includes QUEUED/IN_PROGRESS which are scheduling concepts.
 * CallSessionStatus represents the actual voice call lifecycle.
 */
public enum CallSessionStatus {

    /** Dial request initiated, not yet sent to provider. */
    INITIATED,

    /** Dial request sent to provider (FreeSWITCH originate accepted). */
    DIALING,

    /** Call ringing at destination. */
    RINGING,

    /** Call answered. */
    ANSWERED,

    /** Media playback in progress (VB-1 PLAYFILE). */
    PLAYING,

    /** Playback finished, channel teardown in flight (VB-1 PLAYFILE). */
    PLAYBACK_COMPLETED,

    /** Playback finished, collecting DTMF input from the callee (VB-2). */
    WAITING_FOR_DTMF,

    /** DTMF valid — connecting the caller to an agent (VB-3 CONNECT_BY_AGENT). */
    CONNECTING_AGENT,

    /** Caller and agent bridged — active conversation (VB-3). */
    BRIDGED,

    /** Call completed successfully (normal clearing). */
    COMPLETED,

    /** Call failed (busy, no answer, congestion, etc.). */
    FAILED,

    /** Call cancelled before completion. */
    CANCELLED
}