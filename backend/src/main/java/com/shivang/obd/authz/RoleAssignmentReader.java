package com.shivang.obd.authz;

import java.util.Collection;
import java.util.UUID;

public interface RoleAssignmentReader {

    record Assignment(UUID roleId, Scope scope, UUID resellerId, UUID tenantId) {
    }

    Collection<Assignment> assignmentsFor(UUID userId);
}
