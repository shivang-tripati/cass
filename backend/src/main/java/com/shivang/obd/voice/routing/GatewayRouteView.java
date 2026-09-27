package com.shivang.obd.voice.routing;

import java.util.Objects;
import java.util.UUID;

/**
 * Read-only view of a candidate routing gateway for the voice core.
 * <p>
 * Decouples {@link VoiceRoutingService} from the telephony gateway entity
 * (Modulith acyclic-slice rule): telephony adapts its persistent gateway
 * model into this value object, and voice routing reasons purely over
 * provider-agnostic routing facts. The FreeSWITCH identifiers are opaque
 * strings for the voice core — they flow into {@link VoiceRoute} verbatim.
 *
 * @param id unique gateway id
 * @param displayName human-readable name used in rejection records and logs
 * @param provider provider identifier compared against the DID provider
 * @param freeSwitchGatewayName FreeSWITCH sofia gateway name for origination
 * @param freeSwitchProfile FreeSWITCH sofia profile for origination
 * @param enabled whether the gateway is administratively enabled
 * @param status operational lifecycle status
 */
public record GatewayRouteView(
        UUID id,
        String displayName,
        String provider,
        String freeSwitchGatewayName,
        String freeSwitchProfile,
        boolean enabled,
        GatewayRouteStatus status
) {

    public GatewayRouteView {
        Objects.requireNonNull(id, "id must not be null");
    }
}
