package com.shivang.obd.tenant.dto;

import jakarta.validation.constraints.Size;

public record UpdateTenantRequest(
    @Size(max = 150) String name
) {
}
