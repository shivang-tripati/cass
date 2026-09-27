package com.shivang.obd.did;

/**
 * Allocation provenance of a DID (VB-5C): which pool the current live
 * assignment came from. Kept deliberately separate from
 * {@link AllocationState}: a reseller-pool number is AVAILABLE while
 * sitting in that pool, and revocation must restore the originating
 * pool rather than defaulting to the platform pool.
 */
public enum AllocationSource {
    /** Assigned directly out of the platform pool. */
    PLATFORM,
    /** Assigned out of the reseller pool stamped in {@code reseller_id}. */
    RESELLER
}
