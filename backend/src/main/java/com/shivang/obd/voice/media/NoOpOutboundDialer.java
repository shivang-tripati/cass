package com.shivang.obd.voice.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * NO-OP outbound dialer implementation.
 * <p>
 * Used when no real provider is configured. Fails every dial request
 * with {@link OutboundDialResult#PROVIDER_UNAVAILABLE} instead of
 * pretending a call was placed.
 * <p>
 * This is a placeholder — replace with FreeSWITCH/Asterisk/Kamailio/SIP/cloud provider
 * implementation in later phases.
 */
@Component
@ConditionalOnProperty(prefix = "telephony.freeswitch", name = "enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class NoOpOutboundDialer implements OutboundDialer {

    @Override
    public OutboundDialResponse dial(OutboundDialRequest request) {
        log.warn("NoOpOutboundDialer invoked — no real provider configured. Failing dial request for attempt {}",
            request.callAttemptId());
        return OutboundDialResponse.providerUnavailable();
    }
}