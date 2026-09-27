package com.shivang.obd.contact;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** IDOR-safe scoped access: the tenant boundary is part of every query. */
public interface ContactGroupRepository
        extends JpaRepository<ContactGroupEntity, UUID>, JpaSpecificationExecutor<ContactGroupEntity> {

    Optional<ContactGroupEntity> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<ContactGroupEntity> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    boolean existsByIdAndDeletedAtIsNull(UUID id);
}
