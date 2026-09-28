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
    CONNECT_BY_AGENT,

    /**
     * Rings the contact and ends the call, connecting nobody (VB-7B).
     *
     * <p>The campaign's purpose is the ring event itself: the call is placed and
     * terminated by the platform after a configured window, and nothing is
     * played, collected, or bridged. Completion is therefore a deliberate,
     * platform-controlled termination — the call rang for its full window and was
     * ended on purpose, which is a <em>successful</em> delivery and not a retryable
     * failure.
     *
     * <p>Carries no media, no input collection, and no agent or queue concept;
     * DID, audience, retry, schedule and safety are the ordinary campaign
     * columns.
     */
    MISSED_CALL
}
