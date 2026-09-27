package com.shivang.obd.campaign;

/**
 * Execution lifecycle of a single campaign run.
 * <p>
 * Distinct from {@link CampaignStatus} which describes the campaign's
 * configuration lifecycle. The future execution engine owns runtime
 * transitions (REQUESTED -> RUNNING -> COMPLETED/FAILED).
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
    CANCELLED
}