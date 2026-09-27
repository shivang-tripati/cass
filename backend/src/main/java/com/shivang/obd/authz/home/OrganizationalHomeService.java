package com.shivang.obd.authz.home;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationalHomeService {

    private final OrganizationalHomeRepository homeRepository;

    public OrganizationalHomeService(OrganizationalHomeRepository homeRepository) {
        this.homeRepository = homeRepository;
    }

    @Transactional
    public OrganizationalHomeEntity createTenantHome(UUID userId, UUID tenantId) {
        var home = new OrganizationalHomeEntity();
        home.setUserId(userId);
        home.setHomeType(OrganizationalHomeType.TENANT);
        home.setOrganizationId(tenantId);
        home.setCreatedAt(Instant.now());
        return homeRepository.save(home);
    }

    @Transactional
    public OrganizationalHomeEntity createResellerHome(UUID userId, UUID resellerId) {
        var home = new OrganizationalHomeEntity();
        home.setUserId(userId);
        home.setHomeType(OrganizationalHomeType.RESELLER);
        home.setOrganizationId(resellerId);
        home.setCreatedAt(Instant.now());
        return homeRepository.save(home);
    }

    @Transactional(readOnly = true)
    public Optional<OrganizationalHomeEntity> findByUserId(UUID userId) {
        return homeRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public boolean hasTenantHome(UUID userId) {
        return homeRepository.findByUserId(userId)
            .map(h -> h.getHomeType() == OrganizationalHomeType.TENANT)
            .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean hasResellerHome(UUID userId) {
        return homeRepository.findByUserId(userId)
            .map(h -> h.getHomeType() == OrganizationalHomeType.RESELLER)
            .orElse(false);
    }
}
