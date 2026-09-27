package com.shivang.obd.authz;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleCapabilityRepository extends JpaRepository<RoleCapabilityEntity, UUID> {

    boolean existsByRoleIdAndCapabilityId(UUID roleId, UUID capabilityId);

@Query("SELECT c.key FROM CapabilityEntity c, RoleCapabilityEntity rc WHERE c.id = rc.capabilityId AND rc.roleId = :roleId")
    List<String> findCapabilityKeysByRoleId(@Param("roleId") UUID roleId);
}
