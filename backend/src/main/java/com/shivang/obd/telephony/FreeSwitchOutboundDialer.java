package com.shivang.obd.telephony;

import com.shivang.obd.voice.media.OutboundDialer;
import com.shivang.obd.voice.media.OutboundDialRequest;
import com.shivang.obd.voice.media.OutboundDialResponse;
import com.shivang.obd.voice.media.OutboundDialResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * FreeSWITCH outbound dialer adapter.
 * <p>
 * Implements the {@link OutboundDialer} boundary for FreeSWITCH via
 * Event Socket Library (ESL).
 * <p>
 * Uses bgapi originate for non-blocking call initiation.
 * Returns DIAL_REQUEST_ACCEPTED when FreeSWITCH accepts the originate request.
 * <p>
 * The adapter is only active when {@link FreeSwitchProperties#isEnabled()} is true
 * and all required configuration is present.
 */
@Component
@ConditionalOnProperty(prefix = "telephony.freeswitch", name = "enabled", havingValue = "true")
@Slf4j
public class FreeSwitchOutboundDialer implements OutboundDialer {

    private final FreeSwitchProperties properties;

    public FreeSwitchOutboundDialer(FreeSwitchProperties properties) {
        this.properties = properties;
    }

    @Override
    public OutboundDialResponse dial(OutboundDialRequest request) {
        if (!properties.isEnabled()) {
            log.debug("FreeSWITCH adapter disabled — returning PROVIDER_UNAVAILABLE for attempt {}",
                request.callAttemptId());
            return OutboundDialResponse.providerUnavailable();
        }

        // Validate required configuration
        if (Objects.isNull(properties.getHost()) || properties.getHost().isBlank()) {
            log.error("FreeSWITCH host not configured");
            return OutboundDialResponse.providerUnavailable();
        }
        if (Objects.isNull(properties.getPassword()) || properties.getPassword().isBlank()) {
            log.error("FreeSWITCH password not configured");
            return OutboundDialResponse.providerUnavailable();
        }
        // Gateway derived from routing decision; fallback to global config for backward compat
        String gatewayName = request.routing() != null ? request.routing().freeSwitchGatewayName() : properties.getGateway();
        String profile = request.routing() != null ? request.routing().freeSwitchProfile() : properties.getProfile();
        if (gatewayName == null || gatewayName.isBlank()) {
            log.error("FreeSWITCH gateway not configured");
            return OutboundDialResponse.providerUnavailable();
        }

        try (EslClient eslClient = new EslClient(properties)) {
            eslClient.connect();

            String uuid = eslClient.originate(request.callerId(), request.destinationNumber(), gatewayName, profile);

            log.info("FreeSWITCH originate accepted for attempt {} (providerCallId={})",
                    request.callAttemptId(), uuid);

            return OutboundDialResponse.accepted(uuid);

        } catch (EslException e) {
            String message = e.getMessage();
            log.warn("FreeSWITCH originate failed for attempt {}: {}", request.callAttemptId(), message);

            // Map ESL errors to OutboundDialResult
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("authentication") || lower.contains("auth")) {
                    return OutboundDialResponse.providerUnavailable();
                }
                if (lower.contains("timeout")) {
                    return OutboundDialResponse.providerUnavailable();
                }
                if (lower.contains("connection") || lower.contains("connect")) {
                    return OutboundDialResponse.providerUnavailable();
                }
                if (lower.contains("rejected") || lower.contains("gateway") || lower.contains("no route")) {
                    return OutboundDialResponse.rejected(message);
                }
            }

            return OutboundDialResponse.failed(message != null ? message : "Unknown ESL error");
        }
    }
}