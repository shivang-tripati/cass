package com.shivang.obd.authz;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformRoleAssignmentRepository extends JpaRepository<PlatformRoleAssignmentEntity, UUID> {

    @Query("""
        SELECT r.id FROM PlatformRoleAssignmentEntity pra
        JOIN RoleEntity r ON r.id = pra.roleId
        WHERE pra.userId = :userId
        """)
    List<UUID> findRoleIdsByUserId(@Param("userId") UUID userId);

    boolean existsByUserIdAndRoleId(UUID userId, UUID roleId);

    boolean existsByUserId(UUID userId);
}
