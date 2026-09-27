package com.shivang.obd.reseller;

import com.shivang.obd.authz.RoleAssignmentReader;
import com.shivang.obd.authz.Scope;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Exposes RESELLER-scope assignments to the authorization engine. A
 * membership grants authority only while its organization is alive: a
 * SUSPENDED or soft-deleted reseller yields NO assignments (fail-closed),
 * so its admin loses all reseller/hierarchy capabilities immediately.
 * PLATFORM authority is unaffected and keeps managing inactive
 * organizations.
 */
@Component
class ResellerRoleAssignmentAdapter implements RoleAssignmentReader {

    private final ResellerMembershipRepository membershipRepository;
    private final ResellerRepository resellerRepository;

    ResellerRoleAssignmentAdapter(
        ResellerMembershipRepository membershipRepository,
        ResellerRepository resellerRepository
    ) {
        this.membershipRepository = membershipRepository;
        this.resellerRepository = resellerRepository;
    }

    @Override
    public Collection<Assignment> assignmentsFor(UUID userId) {
        List<ResellerMembershipEntity> memberships =
            membershipRepository.findByUserIdAndStatus(userId, LifecycleStatus.ACTIVE);
        return memberships.stream()
            .flatMap(membership -> resellerRepository
                .findByIdAndDeletedAtIsNull(membership.getResellerId())
                .filter(reseller -> reseller.getStatus() == LifecycleStatus.ACTIVE)
                .map(reseller -> new Assignment(
                    membership.getRoleId(),
                    Scope.RESELLER,
                    membership.getResellerId(),
                    null))
                .stream())
            .toList();
    }
}
