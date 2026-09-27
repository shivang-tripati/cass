package com.shivang.obd.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AGENT account provisioning request. Role is fixed server-side (AGENT,
 * ASSIGNED scope); creation itself is restricted to SUPER_ADMIN.
 */
public record CreateAgentRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(min = 12, max = 128) String password,
    @Size(max = 120) String displayName
) {
}
