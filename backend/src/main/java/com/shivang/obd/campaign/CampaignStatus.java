package com.shivang.obd.campaign;

/**
 * Campaign lifecycle states. Configuration/lifecycle ownership stays with
 * Campaign; per-occurrence execution truth belongs to the future
 * CampaignRun domain — RUNNING/COMPLETED/FAILED here are aggregate
 * rollups driven by the future execution engine.
 */
public enum CampaignStatus {

    /** Being configured; not executable. */
    DRAFT,

    /** Valid and configured for execution (future or recurring). */
    SCHEDULED,

    /** Execution has started. System-driven entry from SCHEDULED. */
    RUNNING,

    /** Intentionally suspended; reversible to SCHEDULED or RUNNING. */
    PAUSED,

    /** Finished successfully. System-driven. */
    COMPLETED,

    /** Terminated unsuccessfully. System-driven. */
    FAILED,

    /** Retained for historical/reference purposes. Terminal state. */
    ARCHIVED
}
