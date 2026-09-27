package com.shivang.obd.voice.routing;

import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.eligibility.VoiceEligibility;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Voice routing service implementing primary/overflow/failover logic.
 * <p>
 * Separates eligibility, capacity, and selection concerns.
 * Policy is controlled by Platform Admin via VoiceRouteProfile.
 * <p>
 * Candidate gateways are observed exclusively through the voice-owned
 * {@link GatewayRoutingPort}; gateway persistence and tenant/reseller
 * allocation policy live behind the telephony adapter, keeping this
 * slice's dependency graph acyclic (telephony -> voice, never the reverse).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceRoutingService {

    private final VoiceRouteProfileRepository profileRepository;
    private final GatewayRoutingPort gatewayRoutingPort;
    private final VoiceCapacityService voiceCapacity;
    private final VoiceEligibility voiceEligibility;
    private final DidRepository didRepository;

    /**
     * Resolves a route for a call based on tenant's routing profile.
     *
     * @param tenantId the tenant
     * @param resellerId the reseller (if applicable)
     * @param destinationNumber the destination number
     * @param didId the DID being used
     * @param callType the type of call
     * @param profileId optional explicit profile ID (if null, uses tenant's default)
     * @return routing decision with selected route and reason
     */
    @Transactional(readOnly = true)
    public VoiceRoutingDecision resolveRoute(
            UUID tenantId,
            UUID resellerId,
            String destinationNumber,
            UUID didId,
            String callType,
            UUID profileId) {

        List<RejectedRoute> rejectedRoutes = new ArrayList<>();

        // 1. Get routing profile
        VoiceRouteProfile profile = getProfile(tenantId, resellerId, profileId);
        if (profile == null) {
            return VoiceRoutingDecision.rejected(
                    VoiceRoutingReason.ROUTE_REJECTED_NO_PROFILE_CONFIGURED.getCode(),
                    List.of()
            );
        }

        // 2. Evaluate voice eligibility (blocklists, DNC, DID validity, etc.)
        var eligibilityResult = voiceEligibility.evaluate(tenantId, destinationNumber, didId);
        if (!eligibilityResult.isAllowed()) {
            return VoiceRoutingDecision.rejected(
                    eligibilityResult.getReasonCode(),
                    List.of(new RejectedRoute(null, null, null, eligibilityResult.getReasonMessage()))
            );
        }

        // 3. Get DID for provider compatibility
        Optional<DidEntity> didOpt = didRepository.findByIdAndDeletedAtIsNull(didId);
        if (didOpt.isEmpty()) {
            return VoiceRoutingDecision.rejected(
                    VoiceRoutingReason.ROUTE_REJECTED_INVALID_DID.getCode(),
                    List.of()
            );
        }
        DidEntity did = didOpt.get();

        // 4. Try primary routes
        var primaryDecision = tryRoutes(
                profile.routesOfType(RouteType.PRIMARY),
                tenantId,
                resellerId,
                did,
                RouteType.PRIMARY,
                true
        );
        if (primaryDecision.isPresent()) {
            // Explainability (architecture §19): a selected decision must still
            // report the alternatives evaluated and rejected before selection.
            List<RejectedRoute> allRejected = new ArrayList<>(rejectedRoutes);
            allRejected.addAll(primaryDecision.getRejected());
            return VoiceRoutingDecision.primary(primaryDecision.get(), VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode(), allRejected);
        }
        rejectedRoutes.addAll(primaryDecision.getRejected());

        // 5. Try overflow routes (if auto overflow enabled)
        if (Boolean.TRUE.equals(profile.getAutoOverflowEnabled())) {
            var overflowDecision = tryRoutes(
                    profile.routesOfType(RouteType.OVERFLOW),
                    tenantId,
                    resellerId,
                    did,
                    RouteType.OVERFLOW,
                    true
            );
            if (overflowDecision.isPresent()) {
                List<RejectedRoute> allRejected = new ArrayList<>(rejectedRoutes);
                allRejected.addAll(overflowDecision.getRejected());
                return VoiceRoutingDecision.overflow(overflowDecision.get(), VoiceRoutingReason.ROUTE_SELECTED_OVERFLOW.getCode(), allRejected);
            }
            rejectedRoutes.addAll(overflowDecision.getRejected());
        } else {
            // Add overflow routes as rejected with policy reason
            for (var entry : profile.routesOfType(RouteType.OVERFLOW)) {
                if (entry.getEnabled()) {
                    rejectedRoutes.add(new RejectedRoute(
                            entry.getGatewayId(),
                            null,
                            RouteType.OVERFLOW,
                            VoiceRoutingReason.ROUTE_REJECTED_AUTO_OVERFLOW_DISABLED.getCode()
                    ));
                }
            }
        }

        // 6. Try failover routes (if auto failover enabled)
        if (Boolean.TRUE.equals(profile.getAutoFailoverEnabled())) {
            var failoverDecision = tryRoutes(
                    profile.routesOfType(RouteType.FAILOVER),
                    tenantId,
                    resellerId,
                    did,
                    RouteType.FAILOVER,
                    true // Architecture §11: failover must also validate capacity (step 7)
            );
            if (failoverDecision.isPresent()) {
                List<RejectedRoute> allRejected = new ArrayList<>(rejectedRoutes);
                allRejected.addAll(failoverDecision.getRejected());
                return VoiceRoutingDecision.failover(failoverDecision.get(), VoiceRoutingReason.ROUTE_SELECTED_FAILOVER.getCode(), allRejected);
            }
            rejectedRoutes.addAll(failoverDecision.getRejected());
        } else {
            // Add failover routes as rejected with policy reason
            for (var entry : profile.routesOfType(RouteType.FAILOVER)) {
                if (entry.getEnabled()) {
                    rejectedRoutes.add(new RejectedRoute(
                            entry.getGatewayId(),
                            null,
                            RouteType.FAILOVER,
                            VoiceRoutingReason.ROUTE_REJECTED_AUTO_FAILOVER_DISABLED.getCode()
                    ));
                }
            }
        }

        // 7. No route available. When every candidate failed for one and the
        // same reason, surface that reason (e.g. DID incompatibility) instead
        // of the generic no-eligible-gateway code.
        return VoiceRoutingDecision.rejected(resolveFinalRejectionReason(rejectedRoutes), rejectedRoutes);
    }

    /**
     * Returns the single common rejection reason when all rejected candidates
     * share it, or the generic NO_ELIGIBLE_GATEWAY reason otherwise.
     */
    private String resolveFinalRejectionReason(List<RejectedRoute> rejectedRoutes) {
        String specific = null;
        for (RejectedRoute r : rejectedRoutes) {
            String reason = r.rejectionReason();
            if (reason == null) {
                continue;
            }
            if (specific == null) {
                specific = reason;
            } else if (!specific.equals(reason)) {
                return VoiceRoutingReason.ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY.getCode();
            }
        }
        return specific != null
                ? specific
                : VoiceRoutingReason.ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY.getCode();
    }

    /**
     * Tries to select a route from the given entries.
     *
     * @param entries route entries to try
     * @param tenantId the tenant
     * @param resellerId the reseller
     * @param did the DID
     * @param routeType the route type
     * @param checkCapacity whether to check capacity (false for failover)
     * @return optional containing selected route or empty with rejection info
     */
    private RoutingAttempt tryRoutes(
            List<VoiceRouteProfileEntry> entries,
            UUID tenantId,
            UUID resellerId,
            DidEntity did,
            RouteType routeType,
            boolean checkCapacity) {

        List<RejectedRoute> rejected = new ArrayList<>();

        // Sort by priority (lower = higher priority)
        List<VoiceRouteProfileEntry> ordered = entries.stream()
                .filter(VoiceRouteProfileEntry::getEnabled)
                .sorted(Comparator.comparingInt(VoiceRouteProfileEntry::getPriority))
                .toList();

        for (VoiceRouteProfileEntry entry : ordered) {
            // Check gateway eligibility
            var gatewayOpt = gatewayRoutingPort.findGateway(entry.getGatewayId());
            if (gatewayOpt.isEmpty()) {
                rejected.add(new RejectedRoute(entry.getGatewayId(), null, routeType, VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_INACTIVE.getCode()));
                continue;
            }
            GatewayRouteView gateway = gatewayOpt.get();

            // Check tenant/reseller authorization (policy hierarchy:
            // a lower-level preference can never bypass a higher-level restriction).
            // Platform-owned gateways are shared; reseller-owned gateways require
            // an explicit allocation for this tenant or its reseller.
            if (!gatewayRoutingPort.isGatewayAuthorized(tenantId, resellerId, gateway)) {
                rejected.add(new RejectedRoute(gateway.id(), gateway.displayName(), routeType, VoiceRoutingReason.ROUTE_REJECTED_TENANT_NOT_AUTHORIZED.getCode()));
                continue;
            }

            // Check gateway state
            if (!gateway.enabled()) {
                rejected.add(new RejectedRoute(gateway.id(), gateway.displayName(), routeType, VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_DISABLED.getCode()));
                continue;
            }
            if (gateway.status() == GatewayRouteStatus.INACTIVE) {
                rejected.add(new RejectedRoute(gateway.id(), gateway.displayName(), routeType, VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_INACTIVE.getCode()));
                continue;
            }
            if (gateway.status() == GatewayRouteStatus.DEGRADED && routeType == RouteType.PRIMARY) {
                // Degraded gateways can be used for overflow/failover but not primary
                rejected.add(new RejectedRoute(gateway.id(), gateway.displayName(), routeType, VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_DEGRADED.getCode()));
                continue;
            }

            // Check campaign-DID/provider compatibility.
            // When the entry pins its own DID, compatibility is enforced against
            // that DID in buildVoiceRoute instead — the route may intentionally
            // switch provider identity on failover (architecture §6:
            // "Gateway A + DID-A → failover → Gateway B + DID-B").
            if (entry.getDidId() == null && !isCompatible(did.getProvider(), gateway)) {
                rejected.add(new RejectedRoute(gateway.id(), gateway.displayName(), routeType, VoiceRoutingReason.ROUTE_REJECTED_DID_INCOMPATIBLE.getCode()));
                continue;
            }

            // Check capacity if required. Delegates the detailed check so the
            // rejection reason distinguishes channel vs CPS exhaustion.
            if (checkCapacity) {
                var capacity = voiceCapacity.checkCapacity(gateway.id(), tenantId);
                if (!capacity.available()) {
                    rejected.add(new RejectedRoute(gateway.id(), gateway.displayName(), routeType,
                            capacity.rejectionReason() != null
                                    ? capacity.rejectionReason()
                                    : VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode()));
                    continue;
                }
            }

            // All checks passed - select this route
            VoiceRoute selectedRoute;
            try {
                selectedRoute = buildVoiceRoute(gateway, entry.getDidId(), did, tenantId);
            } catch (IllegalStateException e) {
                // Profile DID incompatible with gateway
                rejected.add(new RejectedRoute(gateway.id(), gateway.displayName(), routeType, VoiceRoutingReason.ROUTE_REJECTED_DID_INCOMPATIBLE.getCode()));
                continue;
            }
            log.info("Selected {} route: gateway={} (priority={}) for tenant={}",
                    routeType, gateway.displayName(), entry.getPriority(), tenantId);
            return new RoutingAttempt(selectedRoute, routeType, rejected);
        }

        // No route selected
        return RoutingAttempt.empty(rejected);
    }

    /**
     * DID/provider compatibility check: provider string equality
     * (case-insensitive). The gateway must share the DID's provider.
     */
    private static boolean isCompatible(String didProvider, GatewayRouteView gateway) {
        if (didProvider == null || gateway == null || gateway.provider() == null) {
            return false;
        }
        return didProvider.equalsIgnoreCase(gateway.provider());
    }

    /**
     * Builds a VoiceRoute from gateway and optional profile DID.
     * <p>
     * If profile specifies a DID, it MUST be compatible with the gateway.
     * No silent fallback to campaign DID - incompatible profile DID = route rejection.
     *
     * <h2>VB-6E: a profile-pinned DID is validated, not trusted</h2>
     *
     * <p>Pre-VB-6E a pinned DID was filtered only by "row exists and is not
     * soft-deleted" plus a provider-string match. The eligibility gate only ever
     * saw the campaign's <em>requested</em> DID, so a routing profile could pin
     * another tenant's assigned DID, or a pool DID with {@code tenant_id IS
     * NULL}, and that value would be dialed as the caller ID — becoming both the
     * CLI the subscriber sees and the VB-6C daily-dial-limit bucket key. Tenant
     * isolation and compliance were effectively bypassed by a configuration row.
     *
     * <p>Ownership and usability are now checked with the same predicate the
     * rest of the platform uses, scoped to the dialing tenant. A pinned DID that
     * is foreign, inactive, or unassigned makes the route ineligible, which the
     * caller surfaces as a pre-dispatch rejection — no dial, no budget spent.
     */
    private VoiceRoute buildVoiceRoute(GatewayRouteView gateway, UUID profileDidId,
                                       DidEntity campaignDid, UUID tenantId) {
        if (profileDidId != null) {
            // VB-6E: tenant-scoped ownership + ACTIVE + ASSIGNED, in one
            // tenant-bounded query, before the provider compatibility check.
            return didRepository
                    .findByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                            profileDidId, tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED)
                    .filter(d -> isCompatible(d.getProvider(), gateway))
                    .map(d -> new VoiceRoute(gateway.id(), gateway.freeSwitchGatewayName(),
                            gateway.freeSwitchProfile(), gateway.provider(), d.getId(), d.getE164Number()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Profile DID " + profileDidId + " is not a usable DID of tenant "
                                    + tenantId + " for gateway " + gateway.id()));
        }
        // No profile DID specified - use campaign's DID (already validated for compatibility)
        return new VoiceRoute(gateway.id(), gateway.freeSwitchGatewayName(),
                gateway.freeSwitchProfile(), gateway.provider(), campaignDid.getId(), campaignDid.getE164Number());
    }

    /**
     * Gets the routing profile for a tenant.
     */
    private VoiceRouteProfile getProfile(UUID tenantId, UUID resellerId, UUID profileId) {
        if (profileId != null) {
            return profileRepository.findByIdAndTenantIdAndDeletedAtIsNull(profileId, tenantId).orElse(null);
        }
        // Get tenant's default profile (first active one)
        return profileRepository.findFirstByTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(tenantId).orElse(null);
    }

    // Helper class for internal routing attempt result
    private static class RoutingAttempt {
        private final VoiceRoute route;
        private final RouteType routeType;
        private final List<RejectedRoute> rejected;

        RoutingAttempt(VoiceRoute route, RouteType routeType, List<RejectedRoute> rejected) {
            this.route = route;
            this.routeType = routeType;
            this.rejected = rejected;
        }

        static RoutingAttempt empty(List<RejectedRoute> rejected) {
            return new RoutingAttempt(null, null, rejected);
        }

        boolean isPresent() {
            return route != null;
        }

        VoiceRoute get() {
            return route;
        }

        List<RejectedRoute> getRejected() {
            return rejected;
        }
    }
}
