package com.shivang.obd.security;

/**
 * Internal login/refresh outcome. The raw refresh token travels only between
 * the service and the {@link AuthCookieWriter} — never into a response DTO.
 */
public record AuthSessionResult(
    String accessToken,
    String rawRefreshToken,
    long expiresInSeconds
) {
}
