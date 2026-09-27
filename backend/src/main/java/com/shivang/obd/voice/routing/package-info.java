/**
 * Voice routing policy (profiles, primary/overflow/failover selection).
 * <p>
 * Named interface: campaign dialing consumes
 * {@code VoiceRoutingService}/{@code VoiceRoutingDecision} and the
 * {@code VoiceRouting} SPI as the canonical voice routing API; gateway
 * access is adapted behind {@code GatewayRoutingPort} by telephony.
 */
@org.springframework.modulith.NamedInterface("routing")
package com.shivang.obd.voice.routing;
