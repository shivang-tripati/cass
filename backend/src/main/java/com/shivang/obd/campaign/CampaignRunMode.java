package com.shivang.obd.campaign;

/**
 * Intended execution mode. Persisted and validated here; recurrence is
 * evaluated by the future execution engine, not in this module.
 */
public enum CampaignRunMode {

    /** Executes once within its configured schedule window. */
    ONE_TIME,

    /** May execute repeatedly according to its schedule eligibility rules. */
    RECURRING
}
