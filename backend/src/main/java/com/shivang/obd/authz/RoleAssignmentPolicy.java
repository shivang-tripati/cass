package com.shivang.obd.authz;

import com.shivang.obd.common.exception.ResourceNotFoundException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Validates role assignment compatibility with the user's single
 * organizational home. A user has exactly one of: platform (no membership),
 * one reseller, or one tenant. Cross-membership is a violation.
 */
@Service
public class RoleAssignmentPolicy {

    private final RoleRepository roleRepository;
    private final OrganizationalHomeChecker homeChecker;

    public RoleAssignmentPolicy(RoleRepository roleRepository, OrganizationalHomeChecker homeChecker) {
        this.roleRepository = roleRepository;
        this.homeChecker = homeChecker;
    }

    /**
     * RESELLER-scoped roles only. The user must NOT have an active
     * tenant membership (organizational-home invariant).
     */
    public RoleEntity requireAssignableToResellerMembership(UUID userId, UUID roleId) {
        RoleEntity role = requireActiveRole(roleId);
        requireScope(role, Scope.RESELLER, "reseller membership");
        if (homeChecker.hasActiveTenantMembership(userId)) {
            throw new RoleScopeViolationException(
                "User already has an active tenant membership and cannot be assigned "
                    + "a reseller-scoped role.");
        }
        return role;
    }

    /**
     * TENANT-scoped roles only. The user must NOT have an active
     * reseller membership (organizational-home invariant).
     */
    public RoleEntity requireAssignableToTenantMembership(UUID userId, UUID roleId) {
        return requireAssignableToTenantMembership(userId, roleId, Scope.TENANT);
    }

    /**
     * Tenant-membership roles restricted to the given scopes. Used e.g. for
     * AGENT provisioning whose role carries ASSIGNED scope but remains
     * strictly tenant-bound through the membership itself.
     */
    public RoleEntity requireAssignableToTenantMembership(
        UUID userId, UUID roleId, Scope allowedScope, Scope... furtherAllowedScopes
    ) {
        RoleEntity role = requireActiveRole(roleId);
        Set<Scope> allowed = EnumSet.of(allowedScope, furtherAllowedScopes);
        if (!allowed.contains(role.getScope())) {
            throw new RoleScopeViolationException(
                "Role '" + role.getKey() + "' has scope " + role.getScope()
                    + " and cannot be assigned to a tenant membership.");
        }
        if (homeChecker.hasActiveResellerMembership(userId)) {
            throw new RoleScopeViolationException(
                "User already has an active reseller membership and cannot be assigned "
                    + "a tenant-scoped role.");
        }
        return role;
    }

    private RoleEntity requireActiveRole(UUID roleId) {
        return roleRepository.findById(roleId)
            .filter(RoleEntity::isActive)
            .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));
    }

    private void requireScope(RoleEntity role, Scope expected, String target) {
        if (role.getScope() != expected) {
            throw new RoleScopeViolationException(
                "Role '" + role.getKey() + "' has scope " + role.getScope()
                    + " and cannot be assigned to a " + target + ".");
        }
    }

    /**
     * Port for checking existing organizational memberships.
     * Implemented outside authz by the module owning each membership type.
     */
    public interface OrganizationalHomeChecker {
        boolean hasActiveTenantMembership(UUID userId);

        boolean hasActiveResellerMembership(UUID userId);
    }
}
