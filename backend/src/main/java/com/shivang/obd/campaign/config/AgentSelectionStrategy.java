package com.shivang.obd.campaign.config;

/**
 * Agent selection strategy a CONNECT_BY_AGENT campaign may request (VB-7A).
 *
 * <h2>Why this has exactly one constant</h2>
 *
 * <p>Not because round-robin or skills routing were judged unnecessary, but
 * because <em>they do not exist</em>. The platform has precisely one implemented
 * selection rule, introduced in VB-3 and reused verbatim by the VB-4C ACD
 * engine: order eligible candidates by the fewest active reservations, then by
 * stable agent id, so the same state always selects the same agent. Exposing a
 * second name here — even as a "not yet implemented" placeholder — would put an
 * enum constant in the persisted configuration contract and the REST schema that
 * the runtime cannot honour. A campaign would then be configurable in a way that
 * silently does nothing, which is the exact failure mode
 * {@link ConnectByAgentCampaignConfig} exists to eliminate.
 *
 * <p>So the strategy is stated explicitly in configuration and validated, but it
 * states the truth: there is one rule, and it is the existing one. When a second
 * genuinely implemented rule exists, adding a constant here is a one-line change
 * and the ACD engine remains the single place that interprets it.
 *
 * @see com.shivang.obd.voice.agent.AgentRepository#findEligibleOrdered
 * @see com.shivang.obd.voice.acd.AcdService
 */
public enum AgentSelectionStrategy {

    /**
     * The existing deterministic VB-3/VB-4C order: fewest active reservations
     * first, then ascending agent id. Membership, administrative status,
     * runtime availability, endpoint dialability and capacity are all enforced
     * by the agent and ACD layers; this constant only names the ordering.
     */
    LEAST_ACTIVE_RESERVATIONS;

    /** Whether this strategy is the platform's only implemented selection rule. */
    public boolean isImplemented() {
        return true;
    }
}
