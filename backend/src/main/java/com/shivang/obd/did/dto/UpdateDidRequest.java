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

/**
 * DID update request. PUT semantics: configuration blocks are replaced
 * wholesale — omitting optional fields clears them. The E.164 number and
 * ownership (tenantId/resellerId) are intentionally absent: the canonical
 * identity is immutable and reassignment is not part of this phase.
 */
public record UpdateDidRequest(
    @NotBlank @Pattern(regexp = "^[0-9]{1,3}$", message = "Country code must be 1-3 digits without a leading plus.")
    String countryCode,

    @Size(max = 10) String areaCode,

    @Size(max = 100) String circle,

    @NotNull NumberType numberType,

    @NotBlank @Size(max = 50) String provider,

    Set<DidCapability> capabilities,

    @NotNull DidStatus status,

    @NotNull AllocationState allocationState
) {
}
