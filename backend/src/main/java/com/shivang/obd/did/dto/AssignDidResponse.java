package com.shivang.obd.did.dto;

import com.shivang.obd.did.AllocationSource;
import com.shivang.obd.did.AllocationState;
import java.util.UUID;

/**
 * Result of a DID allocation transition (assign/revoke). Echoes the
 * resulting ownership/state so callers observe the authoritative
 * post-transition representation.
 */
public record AssignDidResponse(
    UUID didId,
    AllocationState allocationState,
    AllocationSource allocationSource,
    UUID tenantId,
    UUID resellerId
) {
}
