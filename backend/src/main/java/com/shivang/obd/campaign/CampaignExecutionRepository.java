package com.shivang.obd.campaign;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository with IDOR-safe scoped lookup variants.
 * The caller's tenant boundary is part of the query itself.
 */
public interface CampaignExecutionRepository
        extends JpaRepository<CampaignExecution, UUID>, JpaSpecificationExecutor<CampaignExecution> {

    Optional<CampaignExecution> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<CampaignExecution> findByIdAndDeletedAtIsNull(UUID id);

    /** Reseller-hierarchy lookup (VB-5F): matches executions of any tenant in the given set. */
    Optional<CampaignExecution> findByIdAndTenantIdInAndDeletedAtIsNull(UUID id, Collection<UUID> tenantIds);

    List<CampaignExecution> findByCampaignIdAndTenantIdAndDeletedAtIsNullOrderByRequestedAtDesc(
            UUID campaignId, UUID tenantId);

    Optional<CampaignExecution> findByCampaignIdAndIdempotencyKeyAndDeletedAtIsNull(
            UUID campaignId, String idempotencyKey);

    boolean existsByCampaignIdAndIdempotencyKeyAndDeletedAtIsNull(
            UUID campaignId, String idempotencyKey);

    List<CampaignExecution> findByStatusAndDeletedAtIsNull(CampaignExecutionStatus status);
}