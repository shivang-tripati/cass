package com.shivang.obd.voice.agent;

/**
 * Canonical release reasons for agent reservations (VB-3). Kept as string
 * constants so conditional-UPDATE queries can reference them without
 * depending on higher layers.
 */
public final class ReleaseReasons {

    /** The CONNECT_BY_AGENT flow completed (bridged call ended). */
    public static final String CALL_ENDED = "CALL_ENDED";

    /** Agent leg originate failed — hold never became a call. */
    public static final String ORIGINATE_FAILED = "ORIGINATE_FAILED";

    /** Agent did not answer within the connect timeout. */
    public static final String NO_ANSWER = "NO_ANSWER";

    /** Bridge could not be established after the agent answered. */
    public static final String BRIDGE_FAILED = "BRIDGE_FAILED";

    /** Caller hung up before/during the connection attempt. */
    public static final String CALLER_HANGUP = "CALLER_HANGUP";

    /** Agent configuration/endpoint invalid — connection never attempted. */
    public static final String AGENT_CONFIG_INVALID = "AGENT_CONFIG_INVALID";

    /** Stale-reconciler reclamation (crash/lost event). */
    public static final String STALE_RECLAIM = "STALE_RECLAIM";

    private ReleaseReasons() {
    }
}
