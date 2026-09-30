package com.shivang.obd.campaign;

/**
 * Execution lifecycle of a single campaign run.
 * <p>
 * Distinct from {@link CampaignStatus}, which describes the campaign's
 * configuration and operational-control state and is never derived from
 * executions. This enum is the authoritative runtime record: the orchestrator
 * owns every transition here, and terminal states are derived from real attempt
 * outcomes (all attempts terminal), not from wall-clock or control actions.
 */
public enum CampaignExecutionStatus {

    /** Execution accepted; awaiting engine pickup. */
    REQUESTED,

    /** Engine has started processing this execution. */
    RUNNING,

    /** Engine completed successfully. */
    COMPLETED,

    /** Engine terminated with an error. */
    FAILED,

    /** Execution cancelled before or during processing. */
    CANCELLED;

    /**
     * VB-8D: the terminal outcomes of the execution lifecycle. An execution in
     * one of these has finished, so nothing may dispatch work for it.
     */
    public static final java.util.Set<CampaignExecutionStatus> TERMINAL =
            java.util.Set.of(COMPLETED, FAILED, CANCELLED);

    /**
     * VB-8D: the states in which an execution may still accept dispatch work.
     *
     * <p>Derived from the lifecycle itself rather than chosen per call site:
     * the engine starts {@code REQUESTED} executions and reconciles
     * {@code RUNNING} ones, while {@link #TERMINAL} are its end states. Both the
     * manual attempt path and the scheduler dial path ask this question, so it
     * is answered here once - there is deliberately no second lifecycle policy
     * that could drift from this enum.
     */
    public static final java.util.Set<CampaignExecutionStatus> DISPATCHABLE =
            java.util.Set.of(REQUESTED, RUNNING);

    /** Whether the execution has finished and must not be worked on. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Whether new dispatch work may still be created or performed. */
    public boolean acceptsDispatchWork() {
        return DISPATCHABLE.contains(this);
    }
}