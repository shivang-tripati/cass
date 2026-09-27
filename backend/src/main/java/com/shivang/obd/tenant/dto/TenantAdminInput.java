package com.shivang.obd.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Initial administrator account created together with a tenant. The role
 * is never client-selectable; provisioning always assigns TENANT_ADMIN.
 */
public record TenantAdminInput(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(min = 12, max = 128) String password,
    @Size(max = 120) String displayName
) {
}
