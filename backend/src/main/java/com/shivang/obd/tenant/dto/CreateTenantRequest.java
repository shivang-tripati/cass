package com.shivang.obd.tenant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Tenant provisioning request. {@code resellerId} is honored ONLY for
 * SUPER_ADMIN callers; for a RESELLER_ADMIN the server always derives the
 * reseller from its own organizational home and rejects mismatches.
 */
public record CreateTenantRequest(
    @NotBlank @Size(max = 150) String name,
    @NotBlank @Size(max = 100)
    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug must be lowercase letters, digits and hyphens.")
    String slug,
    UUID resellerId,
    @NotNull @Valid TenantAdminInput admin
) {
}
