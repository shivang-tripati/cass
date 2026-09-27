package com.shivang.obd.audio;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** IDOR-safe scoped access: the tenant boundary is part of every query. */
public interface AudioAssetRepository
        extends JpaRepository<AudioAssetEntity, UUID>, JpaSpecificationExecutor<AudioAssetEntity> {

    Optional<AudioAssetEntity> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<AudioAssetEntity> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByIdAndTenantIdAndDeletedAtIsNullAndStatus(
        UUID id, UUID tenantId, AudioAssetStatus status);

    /** VB-5B readiness: an approved asset without stored audio is not usable. */
    boolean existsByIdAndTenantIdAndDeletedAtIsNullAndStorageReferenceIsNotNull(
        UUID id, UUID tenantId);
}
