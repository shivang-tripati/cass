package com.shivang.obd.security;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * Centralized password policy, reusable by registration, password reset and
 * admin reset in future phases. Deliberately minimal: length-based only
 * (NIST-aligned); no invented character-class rules.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;

    public void validateNewPassword(String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new BusinessException(
                CommonErrorCode.VALIDATION_ERROR, "New password must not be blank.");
        }
        if (newPassword.length() < MIN_LENGTH) {
            throw new BusinessException(
                CommonErrorCode.VALIDATION_ERROR,
                "New password must be at least " + MIN_LENGTH + " characters.");
        }
    }
}
