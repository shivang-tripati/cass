package com.shivang.obd.did;

/**
 * Inbound destination kind for a DID (VB-4D).
 * <p>
 * Persisted on the existing {@code dids} table (V39). {@code null}
 * (absent on the entity) means the DID does not accept inbound calls —
 * the default for every outbound/pool number. No other destination
 * kinds are modeled: IVR, ring groups, and skill routing are out of
 * scope for VB-4D.
 */
public enum DidInboundDestination {

    /** Route inbound calls into a queue (VB-4B model → VB-4C ACD). */
    QUEUE,

    /** Route inbound calls directly to one configured agent. */
    AGENT
}
