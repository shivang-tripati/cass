package com.shivang.obd.reseller;

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
 * R1 regression: a reseller membership only yields an assignment while the
 * backing reseller is ACTIVE and undeleted. SUSPENDED/deleted resellers
 * fail closed.
 */
class ResellerRoleAssignmentAdapterTest {

    private ResellerMembershipRepository membershipRepository;
    private ResellerRepository resellerRepository;
    private ResellerRoleAssignmentAdapter adapter;

    @BeforeEach
    void setUp() {
        membershipRepository = mock(ResellerMembershipRepository.class);
        resellerRepository = mock(ResellerRepository.class);
        adapter = new ResellerRoleAssignmentAdapter(membershipRepository, resellerRepository);
    }

    private ResellerMembershipEntity membership(UUID userId, UUID resellerId, LifecycleStatus status) {
        var m = new ResellerMembershipEntity();
        m.setUserId(userId);
        m.setResellerId(resellerId);
        m.setRoleId(UUID.randomUUID());
        m.setOrganizationalHomeId(UUID.randomUUID());
        m.setStatus(status);
        return m;
    }

    private ResellerEntity reseller(UUID id, LifecycleStatus status, Instant deletedAt) {
        var r = new ResellerEntity();
        r.setId(id);
        r.setName("R");
        r.setSlug("r-" + id);
        r.setStatus(status);
        r.setDeletedAt(deletedAt);
        return r;
    }

    @Test
    void activeUndeletedResellerYieldsAssignment() {
        UUID userId = UUID.randomUUID();
        UUID resellerId = UUID.randomUUID();
        when(membershipRepository.findByUserIdAndStatus(userId, LifecycleStatus.ACTIVE))
            .thenReturn(List.of(membership(userId, resellerId, LifecycleStatus.ACTIVE)));
        when(resellerRepository.findByIdAndDeletedAtIsNull(resellerId))
            .thenReturn(Optional.of(reseller(resellerId, LifecycleStatus.ACTIVE, null)));

        List<RoleAssignmentReader.Assignment> result =
            List.copyOf(adapter.assignmentsFor(userId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).scope()).isEqualTo(Scope.RESELLER);
        assertThat(result.get(0).resellerId()).isEqualTo(resellerId);
    }

    @Test
    void suspendedResellerFailsClosed() {
        UUID userId = UUID.randomUUID();
        UUID resellerId = UUID.randomUUID();
        when(membershipRepository.findByUserIdAndStatus(userId, LifecycleStatus.ACTIVE))
            .thenReturn(List.of(membership(userId, resellerId, LifecycleStatus.ACTIVE)));
        when(resellerRepository.findByIdAndDeletedAtIsNull(resellerId))
            .thenReturn(Optional.of(reseller(resellerId, LifecycleStatus.SUSPENDED, null)));

        assertThat(adapter.assignmentsFor(userId)).isEmpty();
    }

    @Test
    void softDeletedResellerFailsClosed() {
        // Soft-deleted rows are excluded by the query itself; a concurrent
        // delete between load and lookup behaves identically to missing.
        UUID userId = UUID.randomUUID();
        UUID resellerId = UUID.randomUUID();
        when(membershipRepository.findByUserIdAndStatus(userId, LifecycleStatus.ACTIVE))
            .thenReturn(List.of(membership(userId, resellerId, LifecycleStatus.ACTIVE)));
        when(resellerRepository.findByIdAndDeletedAtIsNull(resellerId)).thenReturn(Optional.empty());

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
