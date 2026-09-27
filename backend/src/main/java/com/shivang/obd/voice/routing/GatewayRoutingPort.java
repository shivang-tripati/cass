package com.shivang.obd.voice.routing;

import java.util.Optional;
import java.util.UUID;

/**
 * Port through which the voice routing core observes candidate gateways.
 * <p>
 * Owned by the voice slice and implemented by the telephony adapter
 * (dependency direction: telephony -> voice). Keeps gateway persistence
 * and tenant/reseller authorization policy out of voice routing so the
 * slice dependency graph stays acyclic.
 */
public interface GatewayRoutingPort {

    /**
     * Loads a live (not deleted) gateway view by id.
     *
     * @param gatewayId the candidate gateway id
     * @return the gateway view, or empty when unknown or deleted
     */
    Optional<GatewayRouteView> findGateway(UUID gatewayId);

    /**
     * Checks whether the tenant/reseller is authorized to route calls over
     * the gateway (policy hierarchy: a lower-level preference can never
     * bypass a higher-level restriction).
     *
     * @param tenantId the tenant placing the call (required)
     * @param resellerId the tenant's reseller, if any
     * @param gateway the candidate gateway view
     * @return true when the route is policy-authorized
     */
    boolean isGatewayAuthorized(UUID tenantId, UUID resellerId, GatewayRouteView gateway);
}
