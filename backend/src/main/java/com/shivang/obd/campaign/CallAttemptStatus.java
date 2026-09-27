package com.shivang.obd.campaign;

/**
 * Execution-oriented status of a single call attempt.
 * <p>
 * Distinct from {@link CampaignStatus} (campaign configuration lifecycle)
 * and {@link CampaignExecutionStatus} (execution run lifecycle).
 * The future execution engine owns runtime transitions.
 */
public enum CallAttemptStatus {

    /** Attempt queued; awaiting pickup by execution worker. */
    QUEUED,

    /** Worker has started dialing/processing this attempt. */
    IN_PROGRESS,

    /** Call finished successfully. */
    COMPLETED,

    /** Call terminated with error; retry may apply per campaign retry policy. */
    FAILED,

    /** Attempt cancelled before or during processing. */
    CANCELLED
}