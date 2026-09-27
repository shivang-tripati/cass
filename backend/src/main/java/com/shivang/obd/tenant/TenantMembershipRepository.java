package com.shivang.obd.tenant;

import com.shivang.obd.common.lifecycle.LifecycleStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantMembershipRepository extends JpaRepository<TenantMembershipEntity, UUID> {

    List<TenantMembershipEntity> findByUserIdAndStatus(UUID userId, LifecycleStatus status);

    List<TenantMembershipEntity> findByTenantIdAndStatus(UUID tenantId, LifecycleStatus status);

    Optional<TenantMembershipEntity> findByUserIdAndTenantId(UUID userId, UUID tenantId);

    boolean existsByUserIdAndTenantIdAndStatus(UUID userId, UUID tenantId, LifecycleStatus status);

    boolean existsByUserIdAndStatus(UUID userId, LifecycleStatus status);
}
