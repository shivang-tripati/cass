package com.shivang.obd.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.RoleAssignmentReader;
import com.shivang.obd.authz.Scope;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * R1 regression: a tenant membership only yields an assignment while the
 * backing tenant is ACTIVE and undeleted. SUSPENDED/deleted tenants fail
 * closed.
 */
class TenantRoleAssignmentAdapterTest {

    private TenantMembershipRepository membershipRepository;
    private TenantRepository tenantRepository;
    private TenantRoleAssignmentAdapter adapter;

    @BeforeEach
    void setUp() {
        membershipRepository = mock(TenantMembershipRepository.class);
        tenantRepository = mock(TenantRepository.class);
        adapter = new TenantRoleAssignmentAdapter(membershipRepository, tenantRepository);
    }

    private TenantMembershipEntity membership(UUID userId, UUID tenantId, LifecycleStatus status) {
        var m = new TenantMembershipEntity();
        m.setUserId(userId);
        m.setTenantId(tenantId);
        m.setRoleId(UUID.randomUUID());
        m.setOrganizationalHomeId(UUID.randomUUID());
        m.setStatus(status);
        return m;
    }

    private TenantEntity tenant(UUID id, LifecycleStatus status, Instant deletedAt) {
        var t = new TenantEntity();
        t.setId(id);
        t.setName("T");
        t.setSlug("t-" + id);
        t.setStatus(status);
        t.setDeletedAt(deletedAt);
        return t;
    }

    @Test
    void activeUndeletedTenantYieldsAssignment() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(membershipRepository.findByUserIdAndStatus(userId, LifecycleStatus.ACTIVE))
            .thenReturn(List.of(membership(userId, tenantId, LifecycleStatus.ACTIVE)));
        when(tenantRepository.findByIdAndDeletedAtIsNull(tenantId))
            .thenReturn(Optional.of(tenant(tenantId, LifecycleStatus.ACTIVE, null)));

        List<RoleAssignmentReader.Assignment> result =
            List.copyOf(adapter.assignmentsFor(userId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).scope()).isEqualTo(Scope.TENANT);
        assertThat(result.get(0).tenantId()).isEqualTo(tenantId);
    }

    @Test
    void suspendedTenantFailsClosed() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(membershipRepository.findByUserIdAndStatus(userId, LifecycleStatus.ACTIVE))
            .thenReturn(List.of(membership(userId, tenantId, LifecycleStatus.ACTIVE)));
        when(tenantRepository.findByIdAndDeletedAtIsNull(tenantId))
            .thenReturn(Optional.of(tenant(tenantId, LifecycleStatus.SUSPENDED, null)));

        assertThat(adapter.assignmentsFor(userId)).isEmpty();
    }

    @Test
    void softDeletedTenantFailsClosed() {
        // Soft-deleted rows are excluded by the query itself; a concurrent
        // delete between load and lookup behaves identically to missing.
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(membershipRepository.findByUserIdAndStatus(userId, LifecycleStatus.ACTIVE))
            .thenReturn(List.of(membership(userId, tenantId, LifecycleStatus.ACTIVE)));
        when(tenantRepository.findByIdAndDeletedAtIsNull(tenantId)).thenReturn(Optional.empty());

        assertThat(adapter.assignmentsFor(userId)).isEmpty();
    }

    @Test
    void inactiveMembershipIsNeverConsidered() {
        UUID userId = UUID.randomUUID();
        when(membershipRepository.findByUserIdAndStatus(userId, LifecycleStatus.ACTIVE))
            .thenReturn(List.of());

        assertThat(adapter.assignmentsFor(userId)).isEmpty();
    }
}
