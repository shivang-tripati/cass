package com.shivang.obd.telephony;

import com.shivang.obd.voice.media.GatewayRoute;
import com.shivang.obd.campaign.GatewayRouting;
import com.shivang.obd.voice.routing.VoiceRoute;
import com.shivang.obd.voice.routing.VoiceRouting;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legacy GatewayRouting adapter that delegates to VoiceRouting.
 * <p>
 * Provides backward compatibility for the campaign module.
 */
@Service
@RequiredArgsConstructor
public class GatewayRoutingAdapter implements GatewayRouting {

    private final VoiceRouting voiceRouting;

    @Override
    @Transactional(readOnly = true)
    public Optional<GatewayRoute> resolve(UUID tenantId, UUID resellerId, String provider) {
        return voiceRouting.resolve(tenantId, resellerId, provider)
                .map(vr -> new GatewayRoute(vr.gatewayId(), vr.freeSwitchGatewayName(), vr.freeSwitchProfile(), vr.provider()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GatewayRoute> resolve(UUID tenantId, String provider) {
        return resolve(tenantId, null, provider);
    }
}