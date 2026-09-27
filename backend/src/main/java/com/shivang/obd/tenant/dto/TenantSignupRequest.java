package com.shivang.obd.tenant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Anonymous direct-tenant self-signup. Deliberately carries NO resellerId
 * and no role/org selectors: a self-signup can never join an existing
 * hierarchy or escalate privileges.
 */
public record TenantSignupRequest(
    @NotBlank @Size(max = 150) String name,
    @NotBlank @Size(max = 100)
    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug must be lowercase letters, digits and hyphens.")
    String slug,
    @NotNull @Valid TenantAdminInput admin
) {
}
