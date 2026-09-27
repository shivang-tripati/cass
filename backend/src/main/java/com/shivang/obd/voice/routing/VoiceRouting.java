package com.shivang.obd.voice.routing;

import java.util.Optional;
import java.util.UUID;

/**
 * Voice routing boundary.
 * <p>
 * Resolves the preferred gateway for a tenant/provider combination.
 * Implemented by the telephony adapter (FreeSWITCH).
 * <p>
 * This is the universal voice routing contract, not campaign-specific.
 */
public interface VoiceRouting {

    /**
     * Resolve preferred gateway for a tenant/provider.
     * Uses allocation, status, enabled, provider compatibility and deterministic priority.
     *
     * @param tenantId the tenant
     * @param resellerId the reseller (if applicable)
     * @param provider the provider name (must match DID provider)
     * @return routing decision with gateway details, or empty if none eligible
     */
    Optional<VoiceRoute> resolve(UUID tenantId, UUID resellerId, String provider);

    /**
     * Resolve without explicit reseller.
     */
    default Optional<VoiceRoute> resolve(UUID tenantId, String provider) {
        return resolve(tenantId, null, provider);
    }
}