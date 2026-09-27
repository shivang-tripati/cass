package com.shivang.obd.reseller;

import com.shivang.obd.common.lifecycle.LifecycleStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResellerMembershipRepository extends JpaRepository<ResellerMembershipEntity, UUID> {

    List<ResellerMembershipEntity> findByUserIdAndStatus(UUID userId, LifecycleStatus status);

    List<ResellerMembershipEntity> findByResellerIdAndStatus(UUID resellerId, LifecycleStatus status);

    Optional<ResellerMembershipEntity> findByUserIdAndResellerId(UUID userId, UUID resellerId);

    boolean existsByUserIdAndResellerIdAndStatus(UUID userId, UUID resellerId, LifecycleStatus status);

    boolean existsByUserIdAndStatus(UUID userId, LifecycleStatus status);
}
