package com.shivang.obd.campaign;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * VB-8J: whether this campaign already has an execution in flight.
     *
     * <p>Read under the campaign's row lock (see
     * {@code CampaignExecutionService.execute}), so two concurrent creators
     * serialise and exactly one proceeds. A unique constraint would express the
     * same rule, but the allowed set is a partial subset of
     * {@code CampaignExecutionStatus}, so the lock keeps this a code change
     * rather than a schema change.
     */
    @Query("SELECT COUNT(e) > 0 FROM CampaignExecution e "
            + "WHERE e.campaignId = :campaignId "
            + "AND e.status IN (com.shivang.obd.campaign.CampaignExecutionStatus.REQUESTED, "
            + "com.shivang.obd.campaign.CampaignExecutionStatus.RUNNING) "
            + "AND e.deletedAt IS NULL")
    boolean existsActiveByCampaignId(@Param("campaignId") UUID campaignId);

    /**
     * VB-8J: takes a write lock on the campaign row, scoped to one campaign.
     *
     * <p>Native {@code FOR UPDATE} rather than a global or advisory lock, so
     * concurrent creators of the same campaign serialise while creators of
     * different campaigns do not contend. Released when the caller's transaction
     * commits or rolls back, so it cannot leak.
     *
     * @return 1 when the campaign row was locked, 0 when it is absent or foreign
     */
    @Query(value = "SELECT 1 FROM campaigns WHERE id = :campaignId AND tenant_id = :tenantId "
            + "FOR UPDATE", nativeQuery = true)
    int lockCampaignRow(@Param("campaignId") UUID campaignId,
                        @Param("tenantId") UUID tenantId);
}