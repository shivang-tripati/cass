package com.shivang.obd.reseller;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ResellerRepository
    extends JpaRepository<ResellerEntity, UUID>, JpaSpecificationExecutor<ResellerEntity> {

    boolean existsBySlug(String slug);

    Optional<ResellerEntity> findBySlug(String slug);

    Optional<ResellerEntity> findByCustomDomainIgnoreCase(String customDomain);

    Optional<ResellerEntity> findByIdAndDeletedAtIsNull(UUID id);
}
