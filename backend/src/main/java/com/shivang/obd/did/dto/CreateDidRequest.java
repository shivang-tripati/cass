package com.shivang.obd.did.dto;

import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidCapability;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.did.NumberType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/**
 * DID registration request. The canonical E.164 number is required and
 * strictly format-validated. Ownership fields (tenantId/resellerId) are
 * honored only within the caller's server-derived organizational scope:
 * tenant callers can never override their own tenant, reseller callers
 * may only target tenants inside their managed hierarchy, and platform
 * callers may target any validated organization.
 */
public record CreateDidRequest(
    @NotBlank
    @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$", message = "Must be a valid E.164 number, e.g. +918012345678.")
    String e164Number,

    @NotBlank @Pattern(regexp = "^[0-9]{1,3}$", message = "Country code must be 1-3 digits without a leading plus.")
    String countryCode,

    @Size(max = 10) String areaCode,

    @Size(max = 100) String circle,

    @NotNull NumberType numberType,

    @NotBlank @Size(max = 50) String provider,

    Set<DidCapability> capabilities,

    /** Optional; defaults to ACTIVE. */
    DidStatus status,

    /** Optional; defaults to AVAILABLE. ASSIGNED requires a target tenant in scope. */
    AllocationState allocationState,

    UUID tenantId,
    UUID resellerId
) {
}
