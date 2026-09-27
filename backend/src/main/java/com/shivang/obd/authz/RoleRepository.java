package com.shivang.obd.authz;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    Optional<RoleEntity> findByKeyIgnoreCase(String key);

    List<RoleEntity> findAllByActiveTrue();

    List<RoleEntity> findAllByKeyIn(Collection<String> keys);
}
