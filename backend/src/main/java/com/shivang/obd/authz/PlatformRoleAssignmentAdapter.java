package com.shivang.obd.authz;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads PLATFORM-scope role assignments from platform_role_assignments.
 * These bypass the organizational-home/membership model by design because
 * SUPER_ADMIN transcends tenant/reseller boundaries.
 */
@Component
class PlatformRoleAssignmentAdapter implements RoleAssignmentReader {

    private final PlatformRoleAssignmentRepository repository;

    PlatformRoleAssignmentAdapter(PlatformRoleAssignmentRepository repository) {
        this.repository = repository;
    }

    @Override
    public Collection<Assignment> assignmentsFor(UUID userId) {
        return repository.findRoleIdsByUserId(userId).stream()
            .map(roleId -> new Assignment(roleId, Scope.PLATFORM, null, null))
            .toList();
    }
}
