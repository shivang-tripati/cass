package com.shivang.obd.voice.queue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository for {@link Queue}. All lookups are tenant-scoped or
 * boundary-checked by the service layer (fail closed, 404 on foreign
 * resources — established VB-4A convention).
 */
public interface QueueRepository
        extends JpaRepository<Queue, UUID>, JpaSpecificationExecutor<Queue> {

    Optional<Queue> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<Queue> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(
            UUID tenantId, String name);

    /** Overflow-target existence check within the same tenant. */
    Optional<Queue> findByIdAndTenantIdAndDeletedAtIsNullAndStatusNot(
            UUID id, UUID tenantId, QueueStatus excluded);

    Page<Queue> findAll(Specification<Queue> spec, Pageable pageable);
}
