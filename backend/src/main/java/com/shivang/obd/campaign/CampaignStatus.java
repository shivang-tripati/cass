package com.shivang.obd.campaign;

/**
 * Campaign configuration and operational-control lifecycle.
 *
 * <p>This enum describes the campaign as a configured, dispatchable business
 * object. It is <b>not</b> a record of what the engine did, and it is
 * <b>not</b> derived from executions: several executions may run for one
 * campaign, so no single execution could authoritatively set it.
 *
 * <p>Runtime truth lives in {@link CampaignExecutionStatus}, which is derived
 * from real attempt outcomes. A campaign that is actively dialling reads
 * {@link #SCHEDULED}; whether that work is running, finished or failed is read
 * from its executions.
 *
 * <p>{@link #RUNNING}, {@link #COMPLETED} and {@link #FAILED} are reserved
 * execution-facts, unreachable and not operator-settable. They are retained only
 * because {@code ck_campaigns_status} is a database CHECK constraint (V15) and
 * they are part of the public contract; see {@code CampaignService}.
 */
public enum CampaignStatus {

    /** Being configured. The only editable state. */
    DRAFT,

    /** Configured, validated, and eligible for dispatch. */
    SCHEDULED,

    /** Reserved: describes execution progress. Not a campaign configuration state. */
    RUNNING,

    /**
     * Operator has suspended new dispatch. Retains queued work and does not
     * terminate established calls; progress is unaffected.
     */
    PAUSED,

    /** Reserved: describes execution outcomes. Not a campaign configuration state. */
    COMPLETED,

    /** Reserved: describes execution outcomes. Not a campaign configuration state. */
    FAILED,

    /** Retained for historical/reference purposes. Terminal. */
    ARCHIVED
}
