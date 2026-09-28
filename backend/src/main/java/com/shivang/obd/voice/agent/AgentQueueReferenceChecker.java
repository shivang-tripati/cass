package com.shivang.obd.voice.agent;

import java.util.UUID;

/**
 * Administrative-state check for a queue a campaign references (VB-7A), as a
 * port.
 *
 * <h2>Why this is an interface</h2>
 *
 * <p>Same reasoning as {@link AgentQueueAssignmentTrigger}: queue ownership and
 * lifecycle live in {@code com.shivang.obd.voice.queue}, an internal package of
 * the {@code voice} module, and the campaign module must reach them without a
 * new named interface or a new module edge. This seam carries the read-only
 * question only — "may this tenant configure this queue, and is it
 * administratively usable?" — and nothing about selection.
 *
 * <h2>Configuration state, never runtime state</h2>
 *
 * <p>The answer deliberately covers <em>administrative</em> facts only: does the
 * queue exist, is it live and not soft-deleted, does it belong to this tenant,
 * and is its status {@code ACTIVE}. It says nothing about how many agents are
 * currently available, at capacity, or online. A campaign whose queue is
 * perfectly configured but momentarily has nobody free is still configured, and
 * a momentary vacancy is a runtime fact that the ACD and reservation layers
 * already report through {@link AgentReasons}. Conflating the two would make a
 * campaign permanently unready whenever the contact centre is closed, which is
 * exactly the distinction {@code AgentAdminStatus} vs {@code AgentAvailability}
 * already draws on the agent side.
 *
 * <h2>What the implementation must do</h2>
 *
 * <p>Constrain every lookup by the caller's tenant. A queue belonging to another
 * tenant must be indistinguishable from one that does not exist, so a campaign
 * configuration can never be used to probe another tenant's queue inventory.
 */
public interface AgentQueueReferenceChecker {

    /** The administrative outcome of referencing a queue from a campaign. */
    enum QueueUsability {
        /** Live, same-tenant and {@code ACTIVE}: usable. */
        USABLE,
        /** Missing, soft-deleted, or owned by another tenant — indistinguishable. */
        NOT_ACCESSIBLE,
        /** Same-tenant but administratively not {@code ACTIVE}. */
        NOT_ACTIVE
    }

    /**
     * The administrative usability of {@code queueId} for {@code tenantId}.
     *
     * @param queueId  the campaign-configured queue (may be {@code null})
     * @param tenantId campaign tenant (server-derived)
     */
    QueueUsability usabilityOf(UUID queueId, UUID tenantId);
}
