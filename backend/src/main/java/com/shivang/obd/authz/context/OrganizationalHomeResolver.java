package com.shivang.obd.authz.context;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the user's organizational home from trusted persistence state.
 * Implemented by authz.home module; consumed by security and adapters.
 */
public interface OrganizationalHomeResolver {

    Optional<UUID> resolveTenantId(UUID userId);

    Optional<UUID> resolveResellerId(UUID userId);

    boolean hasTenantHome(UUID userId);

    boolean hasResellerHome(UUID userId);
}
