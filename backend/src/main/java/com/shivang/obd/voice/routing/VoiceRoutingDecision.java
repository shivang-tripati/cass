package com.shivang.obd.voice.routing;

import java.util.List;
import java.util.UUID;

/**
 * Result of a routing decision.
 * <p>
 * Contains the selected route, the route type, and the reason for the decision.
 * Also includes rejected candidates for explainability.
 */
public record VoiceRoutingDecision(
        VoiceRoute selectedRoute,
        RouteType routeType,
        String decisionReason,
        List<RejectedRoute> rejectedRoutes
) {
    public static VoiceRoutingDecision primary(VoiceRoute route, String reason, List<RejectedRoute> rejectedRoutes) {
        return new VoiceRoutingDecision(route, RouteType.PRIMARY, reason, rejectedRoutes);
    }

    public static VoiceRoutingDecision overflow(VoiceRoute route, String reason, List<RejectedRoute> rejectedRoutes) {
        return new VoiceRoutingDecision(route, RouteType.OVERFLOW, reason, rejectedRoutes);
    }

    public static VoiceRoutingDecision failover(VoiceRoute route, String reason, List<RejectedRoute> rejectedRoutes) {
        return new VoiceRoutingDecision(route, RouteType.FAILOVER, reason, rejectedRoutes);
    }

    public static VoiceRoutingDecision rejected(String reason, List<RejectedRoute> rejectedRoutes) {
        return new VoiceRoutingDecision(null, null, reason, rejectedRoutes);
    }
}