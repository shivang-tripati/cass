package com.shivang.obd.voice.routing;

/**
 * Classification of a route within a routing policy.
 */
public enum RouteType {
    /** Normal preferred route for traffic. */
    PRIMARY,

    /** Used when primary has capacity but is at/exceeds configured utilization. */
    OVERFLOW,

    /** Used when primary is unavailable (down, maintenance, unhealthy). */
    FAILOVER
}