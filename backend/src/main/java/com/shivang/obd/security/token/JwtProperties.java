package com.shivang.obd.security.token;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "obd.security.jwt")
public record JwtProperties(
    @NotBlank String secret,
    @NotBlank String issuer,
    @Min(60) long accessTokenExpirationSeconds,
    @Min(300) long refreshTokenTtlSeconds
) {

    public byte[] secretBytes() {
        return secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
