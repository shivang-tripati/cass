package com.shivang.obd.telephony;

import com.shivang.obd.voice.routing.VoiceRoute;
import com.shivang.obd.voice.routing.VoiceRouting;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal telephony routing adapter — implements the voice VoiceRouting.
 * <p>
 * VoiceRoute is the canonical type for the voice core.
 */
@Service
@RequiredArgsConstructor
public class SipGatewayRoutingService implements VoiceRouting {

    private final SipGatewayResolver resolver;

    @Override
    @Transactional(readOnly = true)
    public Optional<VoiceRoute> resolve(UUID tenantId, UUID resellerId, String provider) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return resolver.resolvePreferredGateway(tenantId, resellerId, provider)
                .map(g -> new VoiceRoute(g.getId(), g.getFreeSwitchGatewayName(), g.getFreeSwitchProfile(), g.getProvider()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VoiceRoute> resolve(UUID tenantId, String provider) {
        return resolve(tenantId, null, provider);
    }
}
