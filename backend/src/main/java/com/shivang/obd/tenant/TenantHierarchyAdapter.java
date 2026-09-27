package com.shivang.obd.tenant;

import com.shivang.obd.authz.TenantHierarchyResolver;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class TenantHierarchyAdapter implements TenantHierarchyResolver {

    private final TenantRepository tenantRepository;

    TenantHierarchyAdapter(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    public Optional<UUID> resellerIdOf(UUID tenantId) {
        return tenantRepository.findById(tenantId).map(TenantEntity::getResellerId);
    }
}
