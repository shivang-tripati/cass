package com.shivang.obd.voice.ivr;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped access to IVR trees.
 * <p>
 * Every finder is bounded by {@code tenant_id} and {@code deleted_at}, the
 * platform-wide convention, so a foreign tree simply does not resolve and is
 * indistinguishable from a nonexistent one.
 */
@Repository
public interface IvrTreeRepository extends JpaRepository<IvrTree, UUID> {

    /** Tenant-scoped single lookup — cross-tenant ids fail closed. */
    Optional<IvrTree> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    /** Page of a tenant's trees, newest first, optionally filtered by status. */
    List<IvrTree> findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID tenantId, org.springframework.data.domain.Pageable pageable);

    List<IvrTree> findByTenantIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID tenantId, IvrTreeStatus status,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Whether a tenant already owns a tree derived from a campaign.
     * <p>
     * Makes the create-IVR-from-campaign conversion idempotent at the database
     * level: a retried conversion finds the existing tree instead of creating a
     * second one.
     */
    boolean existsByTenantIdAndSourceCampaignIdAndDeletedAtIsNull(UUID tenantId, UUID campaignId);

    /**
     * Trees still referencing a node as their root. Used to refuse deleting a
     * node that a tree's root pointer depends on.
     */
    @Query(value = "SELECT * FROM ivr_trees WHERE root_node_id = :nodeId AND deleted_at IS NULL",
            nativeQuery = true)
    List<IvrTree> findByRootNodeId(@Param("nodeId") UUID nodeId);
}
