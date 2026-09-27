package com.shivang.obd.authz.home;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationalHomeRepository extends JpaRepository<OrganizationalHomeEntity, UUID> {

    Optional<OrganizationalHomeEntity> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    List<OrganizationalHomeEntity> findByHomeTypeAndOrganizationId(
        OrganizationalHomeType homeType, UUID organizationId);

    List<OrganizationalHomeEntity> findByHomeTypeAndOrganizationIdIn(
        OrganizationalHomeType homeType, Collection<UUID> organizationIds);
}
