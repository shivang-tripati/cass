package com.shivang.obd.did;

/**
 * Allocation state of a DID. AVAILABLE numbers may sit in the platform
 * or reseller pool; ASSIGNED numbers belong to exactly one tenant
 * (enforced by ck_dids_assigned_requires_tenant).
 */
public enum AllocationState {
    AVAILABLE,
    ASSIGNED
}
