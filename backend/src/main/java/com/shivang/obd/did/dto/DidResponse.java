package com.shivang.obd.did.dto;

import com.shivang.obd.did.AllocationSource;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidCapability;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.did.NumberType;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record DidResponse(
    UUID id,
    UUID tenantId,
    UUID resellerId,
    String e164Number,
    String countryCode,
    String areaCode,
    String circle,
    NumberType numberType,
    String provider,
    DidStatus status,
    Set<DidCapability> capabilities,
    AllocationState allocationState,
    AllocationSource allocationSource,
    Instant createdAt,
    Instant updatedAt
) {
}
