package com.shivang.obd.security.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable defaults, deliberately conservative for development. Production
 * values must be reviewed per environment via OBD_SECURITY_LOGIN_RATE_LIMIT_*
 * relaxed-binding environment variables.
 */
@ConfigurationProperties(prefix = "obd.security.login-rate-limit")
public record LoginRateLimitProperties(
    @NotNull Dimension ip,
    @NotNull Dimension account,
    @NotNull Dimension combined
) {

    public record Dimension(@Min(1) int maxAttempts, @Min(1) long windowSeconds) {
    }
}
