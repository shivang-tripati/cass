package com.shivang.obd.telephony;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only resolver for eligible SIP gateways.
 * <p>
 * Future routing/failover will use this. No capacity reservation yet.
 */
@Service
@RequiredArgsConstructor
public class SipGatewayResolver {

    private final SipGatewayRepository gatewayRepository;
    private final SipGatewayAllocationRepository allocationRepository;

    /**
     * Finds gateways eligible for a tenant + provider.
     * Eligibility: gateway ACTIVE+enabled+not deleted, provider matches, allocation grants access.
     * Ordered by allocation priority desc, then gateway priority desc.
     */
    @Transactional(readOnly = true)
    public List<SipGateway> findEligibleGateways(UUID tenantId, UUID resellerId, String provider) {
        if (tenantId == null) {
            return List.of();
        }
        List<SipGatewayAllocation> allocations = allocationRepository.findAllEligibleForTenant(tenantId, resellerId);
        if (allocations.isEmpty()) {
            return List.of();
        }
        // ponytail: O(n) scan, add indexed provider query if gateway table grows large
        return allocations.stream()
                .filter(a -> a.getEnabled() && a.getDeletedAt() == null)
                .map(a -> gatewayRepository.findByIdAndDeletedAtIsNull(a.getGatewayId()).orElse(null))
                .filter(g -> g != null && g.getDeletedAt() == null
                        && Boolean.TRUE.equals(g.getEnabled())
                        && g.getStatus() == SipGatewayStatus.ACTIVE)
                .filter(g -> provider == null || provider.equalsIgnoreCase(g.getProvider()))
                .sorted(Comparator.comparingInt((SipGateway g) -> {
                    // allocation priority takes precedence
                    return allocations.stream()
                            .filter(a -> a.getGatewayId().equals(g.getId()))
                            .map(SipGatewayAllocation::getPriority)
                            .max(Integer::compareTo)
                            .orElse(g.getPriority());
                }).reversed()
                        .thenComparing(Comparator.comparingInt(SipGateway::getPriority).reversed())
                        .thenComparing(SipGateway::getId))
                .collect(Collectors.toList());
    }

    /**
     * DID/provider compatibility check.
     * Currently provider string equality (case-insensitive). Gateway must have same provider as DID.
     */
    public boolean isCompatible(String didProvider, SipGateway gateway) {
        if (didProvider == null || gateway == null || gateway.getProvider() == null) {
            return false;
        }
        return didProvider.equalsIgnoreCase(gateway.getProvider());
    }

    /**
     * Static version for use without instance.
     */
    public static boolean isCompatibleStatic(String didProvider, SipGateway gateway) {
        if (didProvider == null || gateway == null || gateway.getProvider() == null) {
            return false;
        }
        return didProvider.equalsIgnoreCase(gateway.getProvider());
    }

    /**
     * Resolve single preferred gateway for a tenant/provider, or empty if none.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<SipGateway> resolvePreferredGateway(UUID tenantId, UUID resellerId, String provider) {
        List<SipGateway> eligible = findEligibleGateways(tenantId, resellerId, provider);
        return eligible.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(eligible.get(0));
    }
}
