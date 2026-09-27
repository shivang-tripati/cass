package com.shivang.obd.reseller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Initial administrator account created together with a reseller. The role
 * is never client-selectable; provisioning always assigns RESELLER_ADMIN.
 */
public record AdminAccountInput(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(min = 12, max = 128) String password,
    @Size(max = 120) String displayName
) {
}
