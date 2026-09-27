package com.shivang.obd.account.dto;

import com.shivang.obd.common.lifecycle.LifecycleStatus;
import jakarta.validation.constraints.Size;

/**
 * Partial profile/status update. Email, credentials, roles and
 * organizational bindings are intentionally NOT mutable here.
 */
public record UpdateUserRequest(
    @Size(max = 120) String displayName,
    LifecycleStatus status
) {
}
