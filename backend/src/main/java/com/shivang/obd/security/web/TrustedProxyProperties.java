package com.shivang.obd.security.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Client IP resolution strategy.
 *
 * DIRECT (default): always use request.getRemoteAddr().
 * TRUSTED_PROXY: X-Forwarded-For is honored ONLY when remoteAddr matches a
 * configured trusted proxy CIDR; the rightmost non-trusted address in the
 * chain is selected. Untrusted senders can never inject an identity via
 * forwarding headers (fail-closed).
 */
@ConfigurationProperties(prefix = "obd.security.client-ip")
public record TrustedProxyProperties(
    Mode mode,
    List<String> trustedProxies
) {

    public enum Mode { DIRECT, TRUSTED_PROXY }

    public TrustedProxyProperties {
        if (mode == null) {
            mode = Mode.DIRECT;
        }
        if (trustedProxies == null) {
            trustedProxies = List.of();
        }
    }
}
