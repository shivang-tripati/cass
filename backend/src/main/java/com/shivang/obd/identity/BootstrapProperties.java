package com.shivang.obd.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bootstrap SUPER_ADMIN credentials. The bootstrapper stays inactive until
 * both email and password are configured, so production must provide them
 * explicitly via environment while local/dev profiles may carry defaults.
 */
@ConfigurationProperties(prefix = "obd.bootstrap.super-admin")
public record BootstrapProperties(
    String email,
    String password,
    String displayName
) {

    public boolean isConfigured() {
        return email != null && !email.isBlank()
            && password != null && !password.isBlank();
    }
}
