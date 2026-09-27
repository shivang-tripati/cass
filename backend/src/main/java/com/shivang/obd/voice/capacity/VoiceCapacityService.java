package com.shivang.obd.voice.capacity;

import java.util.UUID;

/**
 * Voice capacity boundary.
 * <p>
 * Manages runtime channel capacity and CPS (calls per second) for gateways.
 * Separates capacity CONFIGURATION (maxConcurrentChannels, maxCps on gateway/allocation)
 * from runtime capacity CONSUMPTION (active channels, current CPS).
 */
public interface VoiceCapacityService {

    /**
     * Attempts to reserve a channel on the given gateway for a tenant.
     * Checks both channel capacity and CPS limits.
     *
     * @param gatewayId the gateway to reserve capacity on
     * @param tenantId the tenant making the reservation
     * @return true if reservation succeeded, false if no capacity available
     */
    boolean reserve(UUID gatewayId, UUID tenantId);

    /**
     * Releases a previously reserved channel.
     *
     * @param gatewayId the gateway
     * @param tenantId the tenant
     */
    void release(UUID gatewayId, UUID tenantId);

    /**
     * Checks if a gateway has available capacity for a tenant.
     * Checks both channel capacity and CPS limits.
     *
     * @param gatewayId the gateway
     * @param tenantId the tenant
     * @return true if capacity available
     */
    boolean isAvailable(UUID gatewayId, UUID tenantId);

    /**
     * Detailed capacity check distinguishing WHICH limit was exhausted
     * (channels vs CPS vs gateway state). Enables distinct routing
     * rejection reasons for explainability.
     *
     * @param gatewayId the gateway
     * @param tenantId the tenant
     * @return result with availability and a {@code VoiceRoutingReason} code when rejected
     */
    CapacityCheckResult checkCapacity(UUID gatewayId, UUID tenantId);

    /**
     * Result of a detailed capacity check.
     *
     * @param available whether the route can accept traffic now
     * @param rejectionReason machine-readable reason code when unavailable, null when available
     */
    record CapacityCheckResult(boolean available, String rejectionReason) {

        public static CapacityCheckResult ok() {
            return new CapacityCheckResult(true, null);
        }

        public static CapacityCheckResult rejected(String reasonCode) {
            return new CapacityCheckResult(false, reasonCode);
        }
    }

    /**
     * Gets current channel usage for a gateway.
     *
     * @param gatewayId the gateway
     * @return current active channel count
     */
    int getCurrentUsage(UUID gatewayId);

    /**
     * Gets configured max channel capacity for a gateway.
     *
     * @param gatewayId the gateway
     * @return max concurrent channels, or -1 if unlimited
     */
    int getMaxCapacity(UUID gatewayId);

    /**
     * Gets current CPS usage for a gateway (calls in the last second).
     *
     * @param gatewayId the gateway
     * @return current CPS usage
     */
    int getCurrentCps(UUID gatewayId);

    /**
     * Gets configured max CPS for a gateway.
     *
     * @param gatewayId the gateway
     * @return max CPS, or -1 if unlimited
     */
    int getMaxCps(UUID gatewayId);
}