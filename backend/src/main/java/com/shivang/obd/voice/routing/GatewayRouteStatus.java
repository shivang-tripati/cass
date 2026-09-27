package com.shivang.obd.voice.routing;

/**
 * Operational status of a candidate routing gateway, as observed by the
 * voice routing core.
 * <p>
 * Voice-owned mirror of the telephony gateway lifecycle so routing policy
 * never depends on telephony types (Modulith acyclic-slice rule).
 * The telephony adapter translates its gateway status into this view.
 */
public enum GatewayRouteStatus {
    /** Healthy and routable for every route type. */
    ACTIVE,
    /** Not routable at all. */
    INACTIVE,
    /** Routable for overflow/failover only, never for primary routes. */
    DEGRADED
}
