package com.shivang.obd.security.maintenance;

import com.shivang.obd.security.event.SecurityEventRepository;
import com.shivang.obd.security.token.RefreshTokenRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Batch-oriented retention cleanup for security lifecycle data.
 *
 * <p>Invariants: active refresh tokens are NEVER deletable (the dead-token
 * predicate requires revoked/used/expired state); recent records inside the
 * retention window are never touched; deletion is chunked so no statement
 * loads the table into memory. Each batch runs in its own transaction.</p>
 */
@Service
public class SecurityMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(SecurityMaintenanceService.class);
    private static final int MAX_BATCHES_PER_RUN = 100;
    // Instant import consolidated below

    private final RefreshTokenRepository refreshTokenRepository;
    private final SecurityEventRepository securityEventRepository;
    private final SecurityCleanupProperties properties;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public SecurityMaintenanceService(
        RefreshTokenRepository refreshTokenRepository,
        SecurityEventRepository securityEventRepository,
        SecurityCleanupProperties properties,
        io.micrometer.core.instrument.MeterRegistry meterRegistry
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.securityEventRepository = securityEventRepository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public int cleanupRefreshTokens() {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(properties.refreshTokenRetentionSeconds());
        int totalDeleted = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = refreshTokenRepository.deleteDeadTokensBatch(
                now, cutoff, properties.batchSize());
            totalDeleted += deleted;
            if (deleted < properties.batchSize()) {
                break;
            }
        }
        log.info("Refresh-token cleanup removed {} rows (retention={}d)", totalDeleted,
            properties.refreshTokenRetentionDays());
        counter("obd.security.cleanup.refresh.tokens.deleted").increment(totalDeleted);
        return totalDeleted;
    }

    @Transactional
    public int cleanupSecurityEvents() {
        Instant cutoff = Instant.now().minusSeconds(properties.securityEventRetentionSeconds());
        int totalDeleted = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = securityEventRepository.deleteOlderThanBatch(
                cutoff, properties.batchSize());
            totalDeleted += deleted;
            if (deleted < properties.batchSize()) {
                break;
            }
        }
        log.info("Security-event cleanup removed {} rows (retention={}d)", totalDeleted,
            properties.securityEventRetentionDays());
        counter("obd.security.cleanup.security.events.deleted").increment(totalDeleted);
        return totalDeleted;
    }

    private io.micrometer.core.instrument.Counter counter(String name) {
        return io.micrometer.core.instrument.Counter.builder(name).register(meterRegistry);
    }
}
