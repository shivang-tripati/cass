package com.shivang.obd.tenant;

import com.shivang.obd.common.lifecycle.LifecycleStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TenantRepository
    extends JpaRepository<TenantEntity, UUID>, JpaSpecificationExecutor<TenantEntity> {

    boolean existsBySlug(String slug);

    Optional<TenantEntity> findBySlug(String slug);

    Optional<TenantEntity> findByIdAndDeletedAtIsNull(UUID id);

    List<TenantEntity> findAllByResellerIdAndStatus(UUID resellerId, LifecycleStatus status);
}
