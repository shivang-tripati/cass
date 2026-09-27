package com.shivang.obd.security.maintenance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retention/cleanup policy for security lifecycle data. Development
 * defaults are documented here and MUST be reviewed per environment.
 *
 * schedulerEnabled=false by default: tests and local runs never delete data
 * implicitly; enable per environment (cron applies when enabled).
 */
@ConfigurationProperties(prefix = "obd.security.cleanup")
public record SecurityCleanupProperties(
    boolean schedulerEnabled,
    String cron,
    int batchSize,
    long refreshTokenRetentionDays,
    long securityEventRetentionDays
) {

    public SecurityCleanupProperties {
        if (cron == null || cron.isBlank()) {
            cron = "0 0 3 * * *";
        }
        if (batchSize < 1) {
            batchSize = 500;
        }
        if (refreshTokenRetentionDays < 1) {
            refreshTokenRetentionDays = 30;
        }
        if (securityEventRetentionDays < 1) {
            securityEventRetentionDays = 90;
        }
    }

    public long refreshTokenRetentionSeconds() {
        return refreshTokenRetentionDays * 24L * 3600L;
    }

    public long securityEventRetentionSeconds() {
        return securityEventRetentionDays * 24L * 3600L;
    }
}
