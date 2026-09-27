package com.shivang.obd.telephony;

import com.shivang.obd.voice.routing.GatewayRouteStatus;
import com.shivang.obd.voice.routing.GatewayRouteView;
import com.shivang.obd.voice.routing.GatewayRoutingPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Telephony-side adapter for the voice routing {@link GatewayRoutingPort}.
 * <p>
 * Translates the telephony gateway model (persistence + ownership +
 * tenant/reseller allocation policy) into the provider-agnostic gateway
 * view the voice routing core consumes. Dependency direction is strictly
 * telephony -> voice, keeping the slice graph acyclic.
 */
@Service
public class SipGatewayRoutingAdapter implements GatewayRoutingPort {

    private final SipGatewayRepository gatewayRepository;
    private final GatewayAuthorizationService authorizationService;

    public SipGatewayRoutingAdapter(
            SipGatewayRepository gatewayRepository,
            GatewayAuthorizationService authorizationService) {
        this.gatewayRepository = gatewayRepository;
        this.authorizationService = authorizationService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GatewayRouteView> findGateway(UUID gatewayId) {
        if (gatewayId == null) {
            return Optional.empty();
        }
        return gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)
                .map(SipGatewayRoutingAdapter::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isGatewayAuthorized(UUID tenantId, UUID resellerId, GatewayRouteView gateway) {
        if (gateway == null) {
            return false;
        }
        // Same transaction as findGateway during route resolution, so this
        // lookup is served by the persistence context (no extra SQL).
        return gatewayRepository.findByIdAndDeletedAtIsNull(gateway.id())
                .map(g -> authorizationService.isGatewayAuthorized(tenantId, resellerId, g))
                .orElse(false);
    }

    /** Exposes the entity→view translation for routing tests in other slices. */
    public static GatewayRouteView toView(SipGateway gateway) {
        return new GatewayRouteView(
                gateway.getId(),
                gateway.getName(),
                gateway.getProvider(),
                gateway.getFreeSwitchGatewayName(),
                gateway.getFreeSwitchProfile(),
                Boolean.TRUE.equals(gateway.getEnabled()),
                toStatus(gateway.getStatus()));
    }

    private static GatewayRouteStatus toStatus(SipGatewayStatus status) {
        if (status == null) {
            return GatewayRouteStatus.INACTIVE;
        }
        return switch (status) {
            case ACTIVE -> GatewayRouteStatus.ACTIVE;
            case DEGRADED -> GatewayRouteStatus.DEGRADED;
            case INACTIVE -> GatewayRouteStatus.INACTIVE;
        };
    }
}
