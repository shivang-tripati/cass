package com.shivang.obd.campaign;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository with IDOR-safe lookup variants: every scoped variant applies
 * the caller's tenant boundary inside the query itself, so a foreign or
 * nonexistent campaign is indistinguishable (404) for scoped callers.
 */
public interface CampaignRepository
        extends JpaRepository<CampaignEntity, UUID>, JpaSpecificationExecutor<CampaignEntity> {

    Optional<CampaignEntity> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<CampaignEntity> findByIdAndTenantIdInAndDeletedAtIsNull(UUID id, Collection<UUID> tenantIds);

    /** Platform-scope lookup: no tenant restriction, still excludes deleted rows. */
    Optional<CampaignEntity> findByIdAndDeletedAtIsNull(UUID id);
}
