package com.shivang.obd.security.maintenance;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled entry point for security retention cleanup. Disabled by default
 * (obd.security.cleanup.scheduler-enabled=false) so tests and local runs
 * never delete data implicitly. When enabled, runs daily at 03:00 by default
 * (obd.security.cleanup.cron).
 */
@Component
@EnableConfigurationProperties(SecurityCleanupProperties.class)
public class SecurityCleanupScheduler {

    private final SecurityMaintenanceService maintenanceService;
    private final SecurityCleanupProperties properties;

    public SecurityCleanupScheduler(
        SecurityMaintenanceService maintenanceService,
        SecurityCleanupProperties properties
    ) {
        this.maintenanceService = maintenanceService;
        this.properties = properties;
    }

    @Scheduled(cron = "${obd.security.cleanup.cron:0 0 3 * * *}")
    public void runRetentionCleanup() {
        if (!properties.schedulerEnabled()) {
            return;
        }
        maintenanceService.cleanupRefreshTokens();
        maintenanceService.cleanupSecurityEvents();
    }
}
