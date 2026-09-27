package com.shivang.obd.tenant;

import com.shivang.obd.authz.context.MembershipResolver;
import com.shivang.obd.authz.home.OrganizationalHomeRepository;
import com.shivang.obd.authz.home.OrganizationalHomeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class TenantMembershipResolverAdapter implements MembershipResolver {

    private final OrganizationalHomeRepository homeRepository;

    TenantMembershipResolverAdapter(OrganizationalHomeRepository homeRepository) {
        this.homeRepository = homeRepository;
    }

    @Override
    public Optional<UUID> resolvePrimaryTenantId(UUID userId) {
        return homeRepository.findByUserId(userId)
            .filter(h -> h.getHomeType() == OrganizationalHomeType.TENANT)
            .map(h -> h.getOrganizationId());
    }

    @Override
    public Optional<UUID> resolvePrimaryResellerId(UUID userId) {
        return Optional.empty();
    }

    @Override
    public boolean hasActiveTenantMembership(UUID userId) {
        return homeRepository.findByUserId(userId)
            .map(h -> h.getHomeType() == OrganizationalHomeType.TENANT)
            .orElse(false);
    }

    @Override
    public boolean hasActiveResellerMembership(UUID userId) {
        return false;
    }
}
