package com.shivang.obd.security;

import com.shivang.obd.security.token.JwtProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Sole writer of the auth cookies. The opaque refresh token is delivered as
 * an HttpOnly, SameSite=Strict, Secure cookie scoped to the auth path, so it
 * is never readable by client-side script (XSS mitigation) and never attached
 * on cross-site requests (CSRF mitigation). Clearing uses Max-Age=0.
 */
@Component
@EnableConfigurationProperties(AuthCookieProperties.class)
public class AuthCookieWriter {

    public static final String REFRESH_COOKIE_NAME = "obd_rt";
    public static final String REFRESH_COOKIE_PATH = "/api/v1/auth";
    static final String SAME_SITE = "Strict";

    private final AuthCookieProperties properties;
    private final JwtProperties jwtProperties;

    public AuthCookieWriter(AuthCookieProperties properties, JwtProperties jwtProperties) {
        this.properties = properties;
        this.jwtProperties = jwtProperties;
    }

    /** Sets the refresh token cookie with the configured session TTL. */
    public void addRefreshToken(HttpServletResponse response, String rawRefreshToken) {
        ResponseCookie cookie = baseBuilder()
            .value(rawRefreshToken)
            .maxAge(jwtProperties.refreshTokenTtlSeconds())
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** Expires the refresh token cookie immediately (Max-Age=0). */
    public void clearRefreshToken(HttpServletResponse response) {
        ResponseCookie cookie = baseBuilder()
            .value("")
            .maxAge(0)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder() {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
            .httpOnly(true)
            .secure(properties.secure())
            .sameSite(SAME_SITE)
            .path(REFRESH_COOKIE_PATH);
        if (properties.domain() != null) {
            builder = builder.domain(properties.domain());
        }
        return builder;
    }
}
