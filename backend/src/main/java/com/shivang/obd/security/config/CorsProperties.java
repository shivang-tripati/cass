package com.shivang.obd.security.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Browser origins permitted to call the API cross-origin with credentials
 * (cookies + Authorization headers). Exact-match origins only; wildcard
 * origins are deliberately unsupported together with allowCredentials.
 */
@ConfigurationProperties(prefix = "obd.security.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
            ? List.of()
            : allowedOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .toList();
    }
}
