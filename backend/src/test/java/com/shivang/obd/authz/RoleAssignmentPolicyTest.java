package com.shivang.obd.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.common.exception.ResourceNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoleAssignmentPolicyTest {

    private RoleRepository roleRepository;
    private RoleAssignmentPolicy.OrganizationalHomeChecker homeChecker;
    private RoleAssignmentPolicy policy;

    @BeforeEach
    void setUp() {
        roleRepository = mock(RoleRepository.class);
        homeChecker = mock(RoleAssignmentPolicy.OrganizationalHomeChecker.class);
        policy = new RoleAssignmentPolicy(roleRepository, homeChecker);
    }

    private RoleEntity role(String key, Scope scope) {
        var entity = new RoleEntity();
        entity.setKey(key);
        entity.setScope(scope);
        entity.setActive(true);
        return entity;
    }

    private void stubRole(UUID roleId, RoleEntity entity) {
        when(roleRepository.findById(roleId)).thenReturn(Optional.ofNullable(entity));
    }

    // === RESELLER membership ===

    @Test
    void resellerMembershipAcceptsResellerAdmin() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var expected = role("RESELLER_ADMIN", Scope.RESELLER);
        stubRole(id, expected);
        when(homeChecker.hasActiveTenantMembership(userId)).thenReturn(false);

        assertThat(policy.requireAssignableToResellerMembership(userId, id)).isSameAs(expected);
    }

    @Test
    void resellerMembershipRejectsTenantAdmin() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubRole(roleId, role("TENANT_ADMIN", Scope.TENANT));

        assertThatThrownBy(() -> policy.requireAssignableToResellerMembership(userId, roleId))
            .isInstanceOf(RoleScopeViolationException.class);
    }

    @Test
    void resellerMembershipRejectsSuperAdmin() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubRole(roleId, role("SUPER_ADMIN", Scope.PLATFORM));

        assertThatThrownBy(() -> policy.requireAssignableToResellerMembership(userId, roleId))
            .isInstanceOf(RoleScopeViolationException.class);
    }

    @Test
    void resellerMembershipRejectsAgent() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubRole(roleId, role("AGENT", Scope.ASSIGNED));

        assertThatThrownBy(() -> policy.requireAssignableToResellerMembership(userId, roleId))
            .isInstanceOf(RoleScopeViolationException.class);
    }

    @Test
    void resellerMembershipRejectsUserWithActiveTenantMembership() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubRole(roleId, role("RESELLER_ADMIN", Scope.RESELLER));
        when(homeChecker.hasActiveTenantMembership(userId)).thenReturn(true);

        assertThatThrownBy(() -> policy.requireAssignableToResellerMembership(userId, roleId))
            .isInstanceOf(RoleScopeViolationException.class)
            .hasMessageContaining("tenant membership");
    }

    // === TENANT membership ===

    @Test
    void tenantMembershipAcceptsTenantAdminAndReportViewer() {
        UUID adminId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var admin = role("TENANT_ADMIN", Scope.TENANT);
        stubRole(adminId, admin);
        var viewer = role("REPORT_VIEWER", Scope.TENANT);
        stubRole(viewerId, viewer);
        when(homeChecker.hasActiveResellerMembership(userId)).thenReturn(false);

        assertThat(policy.requireAssignableToTenantMembership(userId, adminId)).isSameAs(admin);
        assertThat(policy.requireAssignableToTenantMembership(userId, viewerId)).isSameAs(viewer);
    }

    @Test
    void tenantMembershipRejectsSuperAdmin() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubRole(roleId, role("SUPER_ADMIN", Scope.PLATFORM));

        assertThatThrownBy(() -> policy.requireAssignableToTenantMembership(userId, roleId))
            .isInstanceOf(RoleScopeViolationException.class);
    }

    @Test
    void tenantMembershipRejectsResellerAdmin() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubRole(roleId, role("RESELLER_ADMIN", Scope.RESELLER));

        assertThatThrownBy(() -> policy.requireAssignableToTenantMembership(userId, roleId))
            .isInstanceOf(RoleScopeViolationException.class);
    }

    @Test
    void tenantMembershipRejectsAgent() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubRole(roleId, role("AGENT", Scope.ASSIGNED));

        assertThatThrownBy(() -> policy.requireAssignableToTenantMembership(userId, roleId))
            .isInstanceOf(RoleScopeViolationException.class);
    }

    @Test
    void tenantMembershipRejectsUserWithActiveResellerMembership() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubRole(roleId, role("TENANT_ADMIN", Scope.TENANT));
        when(homeChecker.hasActiveResellerMembership(userId)).thenReturn(true);

        assertThatThrownBy(() -> policy.requireAssignableToTenantMembership(userId, roleId))
            .isInstanceOf(RoleScopeViolationException.class)
            .hasMessageContaining("reseller membership");
    }

    @Test
    void unknownOrInactiveRoleIsRejected() {
        UUID missing = UUID.randomUUID();
        when(roleRepository.findById(missing)).thenReturn(Optional.empty());
        UUID inactiveId = UUID.randomUUID();
        var inactive = role("TENANT_ADMIN", Scope.TENANT);
        inactive.setActive(false);
        stubRole(inactiveId, inactive);

        assertThatThrownBy(() -> policy.requireAssignableToTenantMembership(missing, missing))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> policy.requireAssignableToTenantMembership(inactiveId, inactiveId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
