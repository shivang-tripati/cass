package com.shivang.obd.authz.context;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves organizational memberships for the authenticated user from
 * trusted persistence state. Implemented by tenant/reseller modules.
 * Security consumes this port to derive OrganizationContext server-side.
 */
public interface MembershipResolver {

    Optional<UUID> resolvePrimaryTenantId(UUID userId);

    Optional<UUID> resolvePrimaryResellerId(UUID userId);

    boolean hasActiveTenantMembership(UUID userId);

    boolean hasActiveResellerMembership(UUID userId);
}
