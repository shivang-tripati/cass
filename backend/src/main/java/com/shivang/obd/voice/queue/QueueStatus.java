package com.shivang.obd.voice.queue;

/** Administrative queue lifecycle (VB-4B). DISABLED is terminal. */
public enum QueueStatus {
    /** Queue can participate in future queue operations. */
    ACTIVE,
    /** Queue remains configured and queryable but not operationally active. */
    INACTIVE,
    /** Administratively disabled — terminal, no further transitions. */
    DISABLED
}
