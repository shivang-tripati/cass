package com.shivang.obd.voice.routing;

import java.util.UUID;

/**
 * A route that was evaluated but not selected, with the reason for rejection.
 */
public record RejectedRoute(
        UUID gatewayId,
        String gatewayName,
        RouteType routeType,
        String rejectionReason
) {}