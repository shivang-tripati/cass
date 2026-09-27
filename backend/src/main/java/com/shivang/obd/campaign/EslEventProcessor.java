package com.shivang.obd.campaign;

/**
 * Interface for FreeSWITCH ESL event processing.
 * <p>
 * Implemented by the telephony module to decouple campaign orchestration
 * from FreeSWITCH-specific implementation details.
 */
public interface EslEventProcessor {

    /**
     * Ensures the ESL event processing loop is running.
     * Called periodically by the campaign orchestrator.
     */
    void ensureEventProcessing();
}
