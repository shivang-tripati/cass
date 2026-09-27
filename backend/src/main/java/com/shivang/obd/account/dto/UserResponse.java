package com.shivang.obd.account.dto;

import com.shivang.obd.authz.home.OrganizationalHomeType;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * User projection for management endpoints. SUPER_ADMIN users carry no
 * organizational home; reseller/tenant users expose their authoritative
 * home binding.
 */
public record UserResponse(
    UUID id,
    String email,
    String displayName,
    LifecycleStatus status,
    OrganizationalHomeType homeType,
    UUID organizationId,
    Instant createdAt
) {
}
