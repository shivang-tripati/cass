package com.shivang.obd.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Transport attributes for the auth cookies (currently the httpOnly refresh
 * cookie). {@code secure} defaults to true so a forgotten profile can never
 * ship a non-Secure credential cookie; HTTP-only dev environments must
 * explicitly opt out.
 */
@ConfigurationProperties(prefix = "obd.security.auth-cookie")
public record AuthCookieProperties(
    @DefaultValue("true") boolean secure,
    String domain
) {

    public AuthCookieProperties {
        if (domain != null && domain.isBlank()) {
            domain = null;
        }
    }
}
