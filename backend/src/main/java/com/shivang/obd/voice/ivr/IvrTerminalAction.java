package com.shivang.obd.voice.ivr;

/**
 * What happens when a caller reaches a terminal node (VB-6F).
 * <p>
 * These are the <em>existing</em> post-interaction actions from the DTMF runtime
 * ({@code DtmfActions}), re-expressed per node so one tree can hang up at one
 * leaf and connect to an agent at another. No new action framework: the
 * dispatch reuses the same {@code AgentConnectTrigger} boundary and the same
 * media-boundary teardown the single-level DTMF path already uses.
 */
public enum IvrTerminalAction {

    /** Hang the call up. The default, and the only action every deployment supports. */
    TERMINATE,

    /**
     * Hand the caller to an agent, through the existing
     * {@code com.shivang.obd.voice.agent.AgentConnectTrigger} boundary. Optional
     * at runtime: an agent-less deployment records a permanent configuration
     * failure rather than failing silently.
     */
    CONNECT_BY_AGENT
}
