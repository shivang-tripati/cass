package com.shivang.obd.voice.media;

import java.util.UUID;

/**
 * Internal routing decision — selected SIP gateway for a call attempt.
 * Provider-agnostic value object; does not expose SIP credentials or internal
 * allocation details. Campaign never sees SipGatewayEntity.
 */
public record GatewayRoute(
        UUID gatewayId,
        String freeSwitchGatewayName,
        String freeSwitchProfile,
        String provider
) {}
