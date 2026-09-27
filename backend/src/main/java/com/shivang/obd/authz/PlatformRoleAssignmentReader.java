package com.shivang.obd.authz;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Reads PLATFORM-scope role assignments that bypass the
 * organizational-home/membership model. SUPER_ADMIN and other platform
 * roles are assigned here because they transcend tenant/reseller boundaries.
 */
public interface PlatformRoleAssignmentReader {

    record Assignment(UUID roleId) {
    }

    Collection<Assignment> platformAssignmentsFor(UUID userId);
}
