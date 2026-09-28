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
    MISSED_CALL;

    /**
     * Whether this type's runtime must deliver audible media to the callee
     * (VB-7C.1).
     *
     * <p>This is the <b>single capability</b> from which both media-related
     * configuration rules are derived, and it replaces the campaign-type
     * membership lists that previously expressed them. Those lists
     * ({@code PLAYFILE || DTMF}, and {@code PLAYFILE || DTMF ||
     * CONNECT_BY_AGENT} for the schedule timezone) were fail-open by
     * construction: they enumerate the types a rule applied to, so adding a type
     * that the rule also applied to silently exempted it from validation. That
     * is not hypothetical — the timezone list omitted {@link #MISSED_CALL}, which
     * let a MISSED_CALL campaign be created, reported ready, and then fail every
     * dial with a permanent {@code EXECUTION_TIMEZONE_INVALID}.
     *
     * <p>Derived rules, both of which are now exhaustively enforced because
     * this switch has no {@code default} arm:
     * <ol>
     *   <li><b>Content is required</b> for a type that plays media, and not for
     *       one that does not. A type that plays nothing has nothing to
     *       configure.</li>
     *   <li><b>TTS is rejected</b> for a type that plays media, because TTS has
     *       no synthesis or playback runtime yet. A type that plays nothing is
     *       unaffected: its content mode is inert, not broken.</li>
     * </ol>
     *
     * <p>Adding a constant makes the compiler reject the build until its
     * capability is stated, which is the guarantee a membership list cannot
     * give. That is the point of routing the rule through the type itself
     * rather than through a registry beside it.
     *
     * @return {@code true} when this type's runtime plays media to the callee
     */
    public boolean playsMedia() {
        return switch (this) {
            case PLAYFILE -> true;
            case DTMF -> true;
            case CONNECT_BY_AGENT -> false;
            case MISSED_CALL -> false;
        };
    }
}
