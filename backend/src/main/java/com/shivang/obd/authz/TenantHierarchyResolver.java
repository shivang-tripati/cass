package com.shivang.obd.authz;

import java.util.Optional;
import java.util.UUID;

public interface TenantHierarchyResolver {

    Optional<UUID> resellerIdOf(UUID tenantId);
}
