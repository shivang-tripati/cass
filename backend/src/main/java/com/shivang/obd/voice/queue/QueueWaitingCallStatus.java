package com.shivang.obd.voice.queue;

/**
 * Waiting-call representation lifecycle (VB-4B). Intentionally small:
 * the distinction that matters is "call is waiting in this queue" versus
 * "call is no longer waiting". WAITING is a valid steady state in VB-4B
 * because no agent assignment exists yet (that is VB-4C).
 */
public enum QueueWaitingCallStatus {
    /** The canonical call is currently waiting in the queue. */
    WAITING,
    /** ACD (VB-4C) reserved an agent for this call — assignment pending telephony (VB-4D). */
    ASSIGNED,
    /** The call was removed from the queue (later flow-owned semantics). */
    REMOVED,
    /** The call left the queue because it was answered/completed downstream. */
    COMPLETED,
    /** The caller abandoned while waiting (queue timeout, VB-4C execution). */
    ABANDONED
}
