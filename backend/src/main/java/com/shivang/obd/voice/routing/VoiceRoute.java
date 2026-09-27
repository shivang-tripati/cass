package com.shivang.obd.voice.routing;

import java.util.UUID;

/**
 * Internal routing decision — selected SIP gateway and DID for a call.
 * <p>
 * Provider-agnostic value object; does not expose SIP credentials or internal
 * allocation details.
 */
public record VoiceRoute(
        UUID gatewayId,
        String freeSwitchGatewayName,
        String freeSwitchProfile,
        String provider,
        UUID didId,
        String didE164Number
) {
    public VoiceRoute(UUID gatewayId, String freeSwitchGatewayName, String freeSwitchProfile, String provider) {
        this(gatewayId, freeSwitchGatewayName, freeSwitchProfile, provider, null, null);
    }
}