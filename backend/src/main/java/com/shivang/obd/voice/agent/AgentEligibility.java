package com.shivang.obd.voice.agent;

import java.util.UUID;

/**
 * Explainable agent eligibility/selection outcome (VB-3), mirroring the
 * VB-0 routing explainability style: machine-readable reason codes, no
 * opaque scoring.
 *
 * @param candidate  the selected agent (present only when eligible)
 * @param endpoint   the dial endpoint resolved for the candidate
 * @param reasonCode machine-readable selection/rejection reason
 */
public record AgentEligibility(
        Agent candidate,
        AgentEndpointEntity endpoint,
        String reasonCode) {

    public static AgentEligibility selected(Agent agent, AgentEndpointEntity endpoint) {
        return new AgentEligibility(agent, endpoint, AgentReasons.SELECTED);
    }

    public static AgentEligibility rejected(String reasonCode) {
        return new AgentEligibility(null, null, reasonCode);
    }

    public boolean isSelected() {
        return candidate != null;
    }
}
