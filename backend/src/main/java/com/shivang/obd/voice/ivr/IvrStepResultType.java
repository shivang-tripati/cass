package com.shivang.obd.voice.ivr;

/**
 * Outcome of one node visit within a call (VB-6F).
 * <p>
 * The single source of truth for "what happened at this node", exactly as
 * {@code DtmfResultType} is for the single-level runtime. {@code CallSessionStatus}
 * carries only the coarse {@code WAITING_FOR_DTMF} state, so duplicating these
 * values on the session would create two sources of truth — the reason
 * {@code V35__add_dtmf_interaction.sql} put the granular result on the
 * interaction row.
 */
public enum IvrStepResultType {

    /** The node's prompt has finished and the runtime is waiting for one digit. */
    WAITING_INPUT,

    /** A valid digit moved the caller to a child node. This visit is finished. */
    ADVANCED,

    /** An unrecognised digit; the caller is being re-asked (retry remains). */
    INVALID_RETRY,

    /** The wait window elapsed with no input; the caller is being re-asked (retry remains). */
    NO_INPUT_RETRY,

    /** A TERMINAL node was reached (or a node exhausted its retries). The call's IVR is over. */
    TERMINAL_REACHED,

    /** The call ended before this visit reached a result (e.g. remote hangup). */
    ABANDONED;

    /** Whether the visit is finished and must never act again. */
    public boolean terminal() {
        return this != WAITING_INPUT;
    }
}
