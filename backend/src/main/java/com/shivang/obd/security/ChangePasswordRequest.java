package com.shivang.obd.security;

import jakarta.validation.constraints.NotBlank;

/**
 * Request-shape validation only (NotBlank). Length and equality rules are
 * enforced by {@link PasswordPolicy} and the change service respectively.
 * Deliberately excludes userId/email/session identifiers: the target user
 * comes exclusively from the authenticated principal.
 */
public record ChangePasswordRequest(
    @NotBlank String currentPassword,
    @NotBlank String newPassword
) {
}
