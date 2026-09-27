package com.shivang.obd.campaign;

/**
 * First-class campaign type. Determines which configuration is required
 * (validated in the service layer) and how the future execution engine
 * drives call flow. Immutable for the lifetime of a campaign.
 */
public enum CampaignType {

    /** Plays approved audio or rendered TTS content to each contact. */
    PLAYFILE,

    /** Plays content, then collects DTMF input from the callee. */
    DTMF,

    /** Places the call and bridges it to an available agent/queue. */
    CONNECT_BY_AGENT
}
