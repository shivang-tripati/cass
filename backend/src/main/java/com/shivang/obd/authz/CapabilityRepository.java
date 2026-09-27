package com.shivang.obd.authz;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapabilityRepository extends JpaRepository<CapabilityEntity, UUID> {

    Optional<CapabilityEntity> findByKeyIgnoreCaseAndActiveTrue(String key);
}
