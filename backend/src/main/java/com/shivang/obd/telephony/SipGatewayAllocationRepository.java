package com.shivang.obd.telephony;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SipGatewayAllocationRepository extends JpaRepository<SipGatewayAllocation, UUID> {

    List<SipGatewayAllocation> findByGatewayIdAndDeletedAtIsNull(UUID gatewayId);

    List<SipGatewayAllocation> findByGatewayIdAndEnabledTrueAndDeletedAtIsNull(UUID gatewayId);

    List<SipGatewayAllocation> findByTenantIdAndEnabledTrueAndDeletedAtIsNull(UUID tenantId);

    List<SipGatewayAllocation> findByResellerIdAndEnabledTrueAndDeletedAtIsNull(UUID resellerId);

    Optional<SipGatewayAllocation> findByGatewayIdAndTenantIdAndDeletedAtIsNull(UUID gatewayId, UUID tenantId);

    Optional<SipGatewayAllocation> findByGatewayIdAndResellerIdAndDeletedAtIsNull(UUID gatewayId, UUID resellerId);

    boolean existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(UUID gatewayId, UUID tenantId);

    boolean existsByGatewayIdAndResellerIdAndEnabledTrueAndDeletedAtIsNull(UUID gatewayId, UUID resellerId);

    Optional<SipGatewayAllocation> findByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(UUID gatewayId, UUID tenantId);

    Optional<SipGatewayAllocation> findByGatewayIdAndResellerIdAndEnabledTrueAndDeletedAtIsNull(UUID gatewayId, UUID resellerId);

    @Query("select a from SipGatewayAllocation a where a.gatewayId = :gatewayId and a.enabled = true and a.deletedAt is null "
            + "and (a.tenantId = :tenantId or a.resellerId = :resellerId)")
    List<SipGatewayAllocation> findEligibleForTenant(
            @Param("gatewayId") UUID gatewayId,
            @Param("tenantId") UUID tenantId,
            @Param("resellerId") UUID resellerId);

    @Query("select a from SipGatewayAllocation a where a.enabled = true and a.deletedAt is null "
            + "and (a.tenantId = :tenantId or a.resellerId = :resellerId)")
    List<SipGatewayAllocation> findAllEligibleForTenant(
            @Param("tenantId") UUID tenantId,
            @Param("resellerId") UUID resellerId);
}
