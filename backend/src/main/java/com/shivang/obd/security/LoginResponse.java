package com.shivang.obd.security;

/**
 * Public auth response body. The refresh token is intentionally absent: it is
 * transported exclusively as an HttpOnly cookie by {@link AuthCookieWriter}.
 */
public record LoginResponse(
    String accessToken,
    String tokenType,
    long expiresInSeconds
) {
}
