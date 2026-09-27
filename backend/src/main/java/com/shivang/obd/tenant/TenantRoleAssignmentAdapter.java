package com.shivang.obd.tenant;

import com.shivang.obd.authz.RoleAssignmentReader;
import com.shivang.obd.authz.Scope;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Exposes TENANT-scope assignments to the authorization engine. A
 * membership grants authority only while its organization is alive:
 * a SUSPENDED or soft-deleted tenant yields NO assignments (fail-closed),
 * so its users lose all tenant capabilities immediately. PLATFORM
 * authority is unaffected and keeps managing inactive organizations.
 */
@Component
class TenantRoleAssignmentAdapter implements RoleAssignmentReader {

    private final TenantMembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;

    TenantRoleAssignmentAdapter(
        TenantMembershipRepository membershipRepository,
        TenantRepository tenantRepository
    ) {
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
    }

    @Override
    public Collection<Assignment> assignmentsFor(UUID userId) {
        List<TenantMembershipEntity> memberships =
            membershipRepository.findByUserIdAndStatus(userId, LifecycleStatus.ACTIVE);
        return memberships.stream()
            .flatMap(membership -> tenantRepository
                .findByIdAndDeletedAtIsNull(membership.getTenantId())
                .filter(tenant -> tenant.getStatus() == LifecycleStatus.ACTIVE)
                .map(tenant -> new Assignment(
                    membership.getRoleId(),
                    Scope.TENANT,
                    null,
                    membership.getTenantId()))
                .stream())
            .toList();
    }
}
