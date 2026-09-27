package com.shivang.obd.voice.routing;

/**
 * Standardized machine-readable reason codes for routing decisions.
 * <p>
 * Used for explainability, observability, and deterministic behavior.
 */
public enum VoiceRoutingReason {

    // Selection reasons
    ROUTE_SELECTED_PRIMARY("Primary route selected"),
    ROUTE_SELECTED_OVERFLOW("Overflow route selected due to primary capacity exhaustion"),
    ROUTE_SELECTED_FAILOVER("Failover route selected due to primary unavailability"),

    // Rejection reasons - authorization
    ROUTE_REJECTED_TENANT_NOT_AUTHORIZED("Tenant not authorized for gateway"),
    ROUTE_REJECTED_RESELLER_NOT_AUTHORIZED("Reseller not authorized for gateway"),
    ROUTE_REJECTED_ENTERPRISE_ONLY("Gateway restricted to enterprise tenants"),
    ROUTE_REJECTED_CAMPAIGN_NOT_ALLOWED("Campaign not allowed to use this route"),
    ROUTE_REJECTED_NO_PROFILE_CONFIGURED("No routing profile configured"),

    // Rejection reasons - DID/CLI compatibility
    ROUTE_REJECTED_DID_INCOMPATIBLE("DID not compatible with gateway provider"),
    ROUTE_REJECTED_DID_NOT_ALLOWED("DID not allowed for this gateway"),

    // Rejection reasons - gateway state
    ROUTE_REJECTED_GATEWAY_DISABLED("Gateway disabled"),
    ROUTE_REJECTED_GATEWAY_MAINTENANCE("Gateway in maintenance"),
    ROUTE_REJECTED_GATEWAY_UNHEALTHY("Gateway unhealthy"),
    ROUTE_REJECTED_GATEWAY_INACTIVE("Gateway inactive"),
    ROUTE_REJECTED_GATEWAY_DEGRADED("Gateway degraded"),

    // Rejection reasons - capacity
    ROUTE_REJECTED_CHANNEL_CAPACITY("Channel capacity exhausted"),
    ROUTE_REJECTED_CPS_CAPACITY("CPS capacity exhausted"),
    ROUTE_REJECTED_CAPACITY_HEADROOM("Capacity headroom not met"),

    // Rejection reasons - policy
    ROUTE_REJECTED_AUTO_OVERFLOW_DISABLED("Automatic overflow disabled by policy"),
    ROUTE_REJECTED_AUTO_FAILOVER_DISABLED("Automatic failover disabled by policy"),
    ROUTE_REJECTED_NO_APPROVED_OVERFLOW("No approved overflow routes"),
    ROUTE_REJECTED_NO_APPROVED_FAILOVER("No approved failover routes"),

    // Rejection reasons - eligibility
    ROUTE_REJECTED_BLOCKLIST("Destination blocked by blocklist"),
    ROUTE_REJECTED_DNC("Destination on DNC list"),
    ROUTE_REJECTED_WHITELIST("Destination not on whitelist"),
    ROUTE_REJECTED_INVALID_DID("Invalid or unassigned DID"),
    ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY("No eligible gateway found"),

    // Generic
    ROUTE_REJECTED_UNKNOWN("Unknown rejection reason");

    private final String description;

    VoiceRoutingReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public String getCode() {
        return name();
    }
}