package com.shivang.obd.voice.dtmf;

/**
 * DTMF terminal-result actions (VB-2/VB-3). Persisted on the interaction
 * snapshot ({@code dtmf_interactions.action_type}) so the action dispatch is
 * auditable and fixed at interaction creation — campaign config changes
 * mid-call never alter the requested action.
 */
public final class DtmfActions {

    /** Default: the call ends after the DTMF result (VB-2 behavior). */
    public static final String TERMINATE = "TERMINATE";

    /** A valid DTMF result connects the caller to an agent (VB-3). */
    public static final String CONNECT_BY_AGENT = "CONNECT_BY_AGENT";

    private DtmfActions() {
    }
}
