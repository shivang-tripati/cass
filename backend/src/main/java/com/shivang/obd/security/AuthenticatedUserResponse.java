package com.shivang.obd.security;

import com.shivang.obd.authz.home.OrganizationalHomeType;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import java.util.Set;
import java.util.UUID;

/**
 * Public identity projection for GET /me. Contains only safe fields by
 * construction: no credentials, tokens, session ids or audit internals.
 */
public record AuthenticatedUserResponse(
    UUID id,
    String email,
    LifecycleStatus status,
    OrganizationalHomeType homeType,
    UUID organizationId,
    Set<String> capabilities
) {
}
