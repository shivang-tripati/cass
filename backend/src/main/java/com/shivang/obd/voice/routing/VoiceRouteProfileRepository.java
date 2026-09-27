package com.shivang.obd.voice.routing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface VoiceRouteProfileRepository
        extends JpaRepository<VoiceRouteProfile, UUID>, JpaSpecificationExecutor<VoiceRouteProfile> {

    Optional<VoiceRouteProfile> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<VoiceRouteProfile> findFirstByTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID tenantId);

    List<VoiceRouteProfile> findByTenantIdAndDeletedAtIsNull(UUID tenantId);
}