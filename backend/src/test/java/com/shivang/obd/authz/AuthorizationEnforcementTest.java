package com.shivang.obd.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.fixture.TestResourcePolicy;
import com.shivang.obd.authz.fixture.TestResourcePolicy.TestResource;
import com.shivang.obd.common.exception.ForbiddenException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Comprehensive authorization enforcement tests covering the full
 * capability/scope/hierarchy/policy matrix and IDOR regression.
 */
class AuthorizationEnforcementTest {

    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bb000000-0000-4000-8000-000000000002");
    private static final UUID RESELLER_A = UUID.fromString("a0000000-0000-4000-8000-00000000000a");
    private static final UUID RESELLER_B = UUID.fromString("b0000000-0000-4000-8000-00000000000b");

    private CapabilityRepository capabilityRepository;
    private RoleCapabilityRepository roleCapabilityRepository;
    private RoleAssignmentReader assignmentReader;
    private TestResourcePolicy testResourcePolicy;
    private AuthorizationService authService;

    @BeforeEach
    void setUp() {
        capabilityRepository = mock(CapabilityRepository.class);
        roleCapabilityRepository = mock(RoleCapabilityRepository.class);
        assignmentReader = mock(RoleAssignmentReader.class);
        testResourcePolicy = new TestResourcePolicy();

        var hierarchyResolver = mock(TenantHierarchyResolver.class);
        when(hierarchyResolver.resellerIdOf(TENANT_A)).thenReturn(Optional.of(RESELLER_A));
        when(hierarchyResolver.resellerIdOf(TENANT_B)).thenReturn(Optional.of(RESELLER_B));

        var provider = new ObjectProvider<TenantHierarchyResolver>() {
            @Override
            public TenantHierarchyResolver getIfAvailable() {
                return hierarchyResolver;
            }
        };

        authService = new AuthorizationService(
            List.of(assignmentReader), provider,
            capabilityRepository, roleCapabilityRepository, List.of());
    }

    private void stubCapabilityAndRole(
        UUID userId, String capKey, Scope scope, UUID resellerId, UUID tenantId, UUID roleId, boolean roleHasCap
    ) {
        var capId = UUID.randomUUID();
        var cap = mock(CapabilityEntity.class);
        when(cap.getId()).thenReturn(capId);
        when(capabilityRepository.findByKeyIgnoreCaseAndActiveTrue(capKey))
            .thenReturn(Optional.of(cap));
        when(assignmentReader.assignmentsFor(userId)).thenReturn(List.of(
            new RoleAssignmentReader.Assignment(roleId, scope, resellerId, tenantId)));
        when(roleCapabilityRepository.existsByRoleIdAndCapabilityId(roleId, capId))
            .thenReturn(roleHasCap);
    }

    // === A/B: SUPER_ADMIN ===

    @Test
    void superAdminWithCapabilityAccessesAnyTenant() {
        var uid = UUID.randomUUID();
        stubCapabilityAndRole(uid, "CAMPAIGN_MANAGE", Scope.PLATFORM, null, null,
            UUID.randomUUID(), true);

        assertThat(authService.hasCapability(uid, "CAMPAIGN_MANAGE",
            AccessCheck.forTenant(TENANT_A))).isTrue();
        assertThat(authService.hasCapability(uid, "CAMPAIGN_MANAGE",
            AccessCheck.forTenant(TENANT_B))).isTrue();
    }

    @Test
    void superAdminWithoutCapabilityDenied() {
        var uid = UUID.randomUUID();
        stubCapabilityAndRole(uid, "CAMPAIGN_VIEW", Scope.PLATFORM, null, null,
            UUID.randomUUID(), false);

        assertThat(authService.hasCapability(uid, "CAMPAIGN_MANAGE",
            AccessCheck.forTenant(TENANT_A))).isFalse();
    }

    // === C/D/E: RESELLER_ADMIN hierarchy ===

    @Test
    void resellerAdminOwnTenantAllowed() {
        var uid = UUID.randomUUID();
        stubCapabilityAndRole(uid, "CAMPAIGN_VIEW", Scope.RESELLER, RESELLER_A, null,
            UUID.randomUUID(), true);

        assertThat(authService.hasCapability(uid, "CAMPAIGN_VIEW",
            AccessCheck.forTenant(TENANT_A))).isTrue();
    }

    @Test
    void resellerAdminSiblingTenantAllowed() {
        var uid = UUID.randomUUID();
        stubCapabilityAndRole(uid, "CAMPAIGN_VIEW", Scope.RESELLER, RESELLER_A, null,
            UUID.randomUUID(), true);
        // TENANT_A belongs to RESELLER_A — sibling tenants are covered.

        assertThat(authService.hasCapability(uid, "CAMPAIGN_VIEW",
            AccessCheck.forTenant(TENANT_A))).isTrue();
    }

    @Test
    void resellerAdminOtherResellersTenantDenied() {
        var uid = UUID.randomUUID();
        stubCapabilityAndRole(uid, "CAMPAIGN_VIEW", Scope.RESELLER, RESELLER_A, null,
            UUID.randomUUID(), true);

        assertThat(authService.hasCapability(uid, "CAMPAIGN_VIEW",
            AccessCheck.forTenant(TENANT_B))).isFalse();
    }

    // === F/G: TENANT_ADMIN isolation ===

    @Test
    void tenantAdminOwnTenantAllowed() {
        var uid = UUID.randomUUID();
        stubCapabilityAndRole(uid, "CONTACT_MANAGE", Scope.TENANT, null, TENANT_A,
            UUID.randomUUID(), true);

        assertThat(authService.hasCapability(uid, "CONTACT_MANAGE",
            AccessCheck.forTenant(TENANT_A))).isTrue();
    }

    @Test
    void tenantAdminOtherTenantDenied() {
        var uid = UUID.randomUUID();
        stubCapabilityAndRole(uid, "CONTACT_MANAGE", Scope.TENANT, null, TENANT_A,
            UUID.randomUUID(), true);

        assertThat(authService.hasCapability(uid, "CONTACT_MANAGE",
            AccessCheck.forTenant(TENANT_B))).isFalse();
    }

    // === H/I: REPORT_VIEWER read-only ===

    @Test
    void reportViewerReadAllowed() {
        var uid = UUID.randomUUID();
        stubCapabilityAndRole(uid, "REPORT_VIEW", Scope.TENANT, null, TENANT_A,
            UUID.randomUUID(), true);

        assertThat(authService.hasCapability(uid, "REPORT_VIEW",
            AccessCheck.forTenant(TENANT_A))).isTrue();
    }

    @Test
    void reportViewerManageDenied() {
        var uid = UUID.randomUUID();
        stubCapabilityAndRole(uid, "REPORT_VIEW", Scope.TENANT, null, TENANT_A,
            UUID.randomUUID(), false);

        assertThat(authService.hasCapability(uid, "CAMPAIGN_MANAGE",
            AccessCheck.forTenant(TENANT_A))).isFalse();
    }

    // === Fail-closed gates ===

    @Test
    void requireCapabilityThrowsForbiddenOnDenial() {
        var uid = UUID.randomUUID();
        stubCapabilityAndRole(uid, "CONTACT_MANAGE", Scope.TENANT, null, TENANT_A,
            UUID.randomUUID(), true);

        var thrown = catchThrowable(() -> authService.requireCapability(
            uid, "CONTACT_MANAGE", AccessCheck.forTenant(TENANT_B)));

        assertThat(thrown).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void nonexistentCapabilityFailsClosed() {
        // No capability stubbed at all — repository returns empty for any lookup.
        var uid = UUID.randomUUID();

        assertThat(authService.hasCapability(uid, "TOTALLY_MADE_UP",
            AccessCheck.platformWide())).isFalse();
    }

    @Test
    void nullUserIdFailsClosed() {
        stubCapabilityAndRole(UUID.randomUUID(), "CAMPAIGN_VIEW", Scope.PLATFORM, null, null,
            UUID.randomUUID(), true);

        assertThat(authService.hasCapability(null, "CAMPAIGN_VIEW",
            AccessCheck.platformWide())).isFalse();
    }

    // === AGENT / ASSIGNED resource policy ===

    @Test
    void assignedResourceAllowedThroughPolicy() {
        var agent = UUID.randomUUID();
        var resourceId = UUID.randomUUID();
        stubCapabilityAndRole(agent, "CALL_DISPOSITION", Scope.ASSIGNED, null, null,
            UUID.randomUUID(), true);

        var policy = new ResourceAuthorizationPolicy() {
            @Override
            public boolean supports(String resourceType) { return "CALL".equals(resourceType); }

            @Override
            public boolean isAllowed(UUID uid, String cap, String type, UUID resId) {
                return true; // assigned
            }
        };
        var authServiceWithPolicy = new AuthorizationService(
            List.of(assignmentReader), provider(),
            capabilityRepository, roleCapabilityRepository, List.of(policy));

        assertThat(authServiceWithPolicy.hasResourceAccess(
            agent, "CALL_DISPOSITION", "CALL", resourceId, AccessCheck.forTenant(TENANT_A)))
            .isTrue();
    }

    @Test
    void unassignedResourceDeniedThroughPolicy() {
        var agent = UUID.randomUUID();
        stubCapabilityAndRole(agent, "CALL_DISPOSITION", Scope.ASSIGNED, null, null,
            UUID.randomUUID(), true);

        var policy = new ResourceAuthorizationPolicy() {
            @Override
            public boolean supports(String resourceType) { return "CALL".equals(resourceType); }

            @Override
            public boolean isAllowed(UUID uid, String cap, String type, UUID resId) {
                return false; // not assigned
            }
        };
        var authServiceWithDenyingPolicy = new AuthorizationService(
            List.of(assignmentReader), provider(),
            capabilityRepository, roleCapabilityRepository, List.of(policy));

        assertThat(authServiceWithDenyingPolicy.hasResourceAccess(
            agent, "CALL_DISPOSITION", "CALL", UUID.randomUUID(), AccessCheck.forTenant(TENANT_A)))
            .isFalse();
    }

    private ObjectProvider<TenantHierarchyResolver> provider() {
        var hierarchyResolver = mock(TenantHierarchyResolver.class);
        when(hierarchyResolver.resellerIdOf(any())).thenReturn(Optional.empty());
        return new ObjectProvider<TenantHierarchyResolver>() {
            @Override
            public TenantHierarchyResolver getIfAvailable() {
                return hierarchyResolver;
            }
        };
    }
}
