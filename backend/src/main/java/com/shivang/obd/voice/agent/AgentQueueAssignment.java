package com.shivang.obd.voice.agent;

import java.util.UUID;

/**
 * Outcome of an outbound queue-scoped agent assignment attempt (VB-7A).
 *
 * <p>Deliberately narrow: it carries the <em>decision</em> (which agent, or
 * which existing reason code) and nothing about telephony. The caller — the
 * campaign-side connection service — owns the leg, the originate and the
 * bridge, exactly as it already does for the tenant-wide VB-3 path. This is the
 * same division of labour {@code AcdService} documents for itself: ACD
 * determines and reserves, it performs no telephony.
 *
 * <p>{@code reasonCode} is the failing authority's <em>own</em> explanation,
 * passed through verbatim: an {@link AgentReasons} value for agent-level
 * outcomes (all members at capacity, say) or an
 * {@code com.shivang.obd.voice.acd.AcdReasons} value for queue-level ones. No
 * campaign-specific selection reason codes are introduced, and no existing code
 * is renamed or re-mapped — a failure reads the same whether it came from the
 * outbound queue path or from the inbound one.
 */
public record AgentQueueAssignment(
        boolean assigned,
        UUID agentId,
        String reasonCode) {

    /** ACD reserved an agent; the caller must now create and originate its leg. */
    public static AgentQueueAssignment assigned(UUID agentId) {
        return new AgentQueueAssignment(true, agentId, AgentReasons.SELECTED);
    }

    /** ACD declined; {@code reasonCode} is an existing {@link AgentReasons} value. */
    public static AgentQueueAssignment rejected(String reasonCode) {
        return new AgentQueueAssignment(false, null, reasonCode);
    }
}
