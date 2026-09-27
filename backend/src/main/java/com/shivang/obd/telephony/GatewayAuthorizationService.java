package com.shivang.obd.telephony;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant/reseller gateway authorization for routing (policy hierarchy).
 * <p>
 * Enforces the architecture rule that a campaign-level preference can never
 * bypass a higher-level restriction: a gateway is only routable when the
 * tenant (or its reseller) holds an explicit allocation, or the gateway is
 * platform-owned shared infrastructure.
 * <p>
 * Per-tenant grants on platform gateways remain governed by allocations in
 * the capacity layer ({@code VoiceCapacityServiceImpl}); this service decides
 * whether a route may be considered at all.
 */
@Service
@RequiredArgsConstructor
public class GatewayAuthorizationService {

    private final SipGatewayAllocationRepository allocationRepository;

    /**
     * Checks whether the tenant/reseller is authorized to route calls over the gateway.
     *
     * @param tenantId the tenant placing the call (required)
     * @param resellerId the tenant's reseller, if any
     * @param gateway the candidate gateway
     * @return true when the route is policy-authorized
     */
    @Transactional(readOnly = true)
    public boolean isGatewayAuthorized(UUID tenantId, UUID resellerId, SipGateway gateway) {
        if (gateway == null || tenantId == null) {
            return false;
        }

        // Platform-owned gateways are shared infrastructure; any tenant may be
        // considered for them. Per-tenant grants are enforced by allocation at
        // the capacity layer.
        if (gateway.getOwnerType() == SipGatewayOwnerType.PLATFORM) {
            return true;
        }

        // Explicit tenant allocation grants access.
        if (allocationRepository.existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                gateway.getId(), tenantId)) {
            return true;
        }

        // Reseller-level allocation for the tenant's own reseller grants access.
        // Note: the gateway's owner reseller is deliberately NOT used here —
        // ownership of a trunk does not authorize other resellers' traffic.
        if (resellerId != null
                && allocationRepository.existsByGatewayIdAndResellerIdAndEnabledTrueAndDeletedAtIsNull(
                        gateway.getId(), resellerId)) {
            return true;
        }

        return false;
    }
}
