package com.shivang.obd.campaign;

import com.shivang.obd.voice.media.GatewayRoute;

import java.util.Optional;
import java.util.UUID;

/**
 * Internal telephony routing boundary.
 * Implemented by telephony module to keep Campaign unaware of SipGateway internals.
 */
public interface GatewayRouting {

    /**
     * Resolve preferred gateway for a tenant/provider.
     * Uses allocation, status, enabled, provider compatibility and deterministic priority.
     */
    Optional<GatewayRoute> resolve(UUID tenantId, UUID resellerId, String provider);

    default Optional<GatewayRoute> resolve(UUID tenantId, String provider) {
        return resolve(tenantId, null, provider);
    }
}
