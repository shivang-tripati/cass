package com.shivang.obd.did;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository with IDOR-safe scoped lookup variants: the caller's tenant
 * boundary is part of the query itself, so a foreign DID and a
 * nonexistent DID are indistinguishable (both 404) for tenant-scoped
 * callers. Reseller/platform visibility is resolved in
 * {@link DidService#findVisible} because pool numbers (tenant_id NULL)
 * are also reseller-visible.
 */
public interface DidRepository
        extends JpaRepository<DidEntity, UUID>, JpaSpecificationExecutor<DidEntity> {

    Optional<DidEntity> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<DidEntity> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByE164NumberAndDeletedAtIsNull(String e164Number);

    boolean existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
        UUID id, UUID tenantId, DidStatus status, AllocationState allocationState);

    /**
     * Inbound routing lookup (VB-4D): resolve a DID by the destination
     * number FreeSWITCH reported. Uniqueness of the live E.164 is a
     * database invariant (V16 {@code uq_dids_e164_live}); tenant ownership
     * is re-verified against the DID's own tenant by the caller before any
     * tenant-scoped state is created.
     */
    Optional<DidEntity> findByE164NumberAndDeletedAtIsNull(String e164Number);

    /**
     * Agent-originated outbound calls (VB-4E): deterministically pick the
     * tenant's default CLI DID (lowest id) when the request does not name
     * one. Deterministic ordering keeps routing decisions reproducible.
     */
    Optional<DidEntity>
            findFirstByTenantIdAndDeletedAtIsNullAndStatusAndAllocationStateOrderByIdAsc(
                    UUID tenantId, DidStatus status, AllocationState allocationState);

    // === VB-5C atomic allocation transitions (conditional UPDATE; the DB
    // is the source of truth — no read-check-write race window) ===

    /**
     * Atomically claims an AVAILABLE platform-pool DID for a tenant.
     *
     * @return rows updated (1 = won the race, 0 = not available/eligible)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DidEntity d SET d.tenantId = :tenantId, d.resellerId = null, "
            + "d.allocationState = com.shivang.obd.did.AllocationState.ASSIGNED, "
            + "d.allocationSource = com.shivang.obd.did.AllocationSource.PLATFORM "
            + "WHERE d.id = :didId AND d.allocationState = com.shivang.obd.did.AllocationState.AVAILABLE "
            + "AND d.deletedAt IS NULL AND d.tenantId IS null AND d.resellerId IS null")
    int assignFromPlatformPoolToTenant(@Param("didId") UUID didId, @Param("tenantId") UUID tenantId);

    /**
     * Atomically hands an AVAILABLE platform-pool DID to a reseller pool.
     * Pool representation stays resellerId-only (tenantId null, AVAILABLE).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DidEntity d SET d.resellerId = :resellerId, "
            + "d.allocationSource = com.shivang.obd.did.AllocationSource.RESELLER "
            + "WHERE d.id = :didId AND d.allocationState = com.shivang.obd.did.AllocationState.AVAILABLE "
            + "AND d.deletedAt IS NULL AND d.tenantId IS null AND d.resellerId IS null")
    int assignFromPlatformPoolToReseller(@Param("didId") UUID didId, @Param("resellerId") UUID resellerId);

    /**
     * Atomically assigns an AVAILABLE reseller-pool DID to one of that
     * reseller's tenants. The reseller stamp must already match.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DidEntity d SET d.tenantId = :tenantId, "
            + "d.allocationState = com.shivang.obd.did.AllocationState.ASSIGNED "
            + "WHERE d.id = :didId AND d.allocationState = com.shivang.obd.did.AllocationState.AVAILABLE "
            + "AND d.deletedAt IS NULL AND d.tenantId IS null AND d.resellerId = :resellerId")
    int assignFromResellerPoolToTenant(
            @Param("didId") UUID didId, @Param("resellerId") UUID resellerId, @Param("tenantId") UUID tenantId);

    /**
     * Atomically revokes a tenant assignment, restoring the DID to the
     * pool recorded in {@code allocation_source} (reseller pool when
     * RESELLER, platform pool otherwise).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DidEntity d SET d.tenantId = null, "
            + "d.allocationState = com.shivang.obd.did.AllocationState.AVAILABLE, "
            + "d.allocationSource = CASE WHEN d.allocationSource = "
            + "com.shivang.obd.did.AllocationSource.RESELLER "
            + "THEN com.shivang.obd.did.AllocationSource.RESELLER ELSE null END "
            + "WHERE d.id = :didId AND d.allocationState = com.shivang.obd.did.AllocationState.ASSIGNED "
            + "AND d.deletedAt IS NULL AND d.tenantId = :tenantId")
    int revokeFromTenant(@Param("didId") UUID didId, @Param("tenantId") UUID tenantId);

    /**
     * Atomically revokes a reseller-pool hold, returning the DID to the
     * platform pool (stamp cleared, provenance reset to pristine). A
     * reseller-pool hold is AVAILABLE by design (resellerId set, tenantId
     * null); the tenant-assignment path uses {@link #revokeFromTenant}.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DidEntity d SET d.resellerId = null, "
            + "d.allocationSource = null, "
            + "d.allocationState = com.shivang.obd.did.AllocationState.AVAILABLE "
            + "WHERE d.id = :didId AND d.allocationState = com.shivang.obd.did.AllocationState.AVAILABLE "
            + "AND d.deletedAt IS NULL AND d.tenantId IS null AND d.resellerId = :resellerId "
            + "AND d.allocationSource = com.shivang.obd.did.AllocationSource.RESELLER")
    int revokeFromReseller(@Param("didId") UUID didId, @Param("resellerId") UUID resellerId);
}
