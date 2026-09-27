package com.shivang.obd.security.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.security.event.SecurityEventRepository;
import com.shivang.obd.security.token.RefreshTokenRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SecurityMaintenanceServiceTest {

    private RefreshTokenRepository refreshTokenRepository;
    private SecurityEventRepository securityEventRepository;
    private SecurityMaintenanceService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        securityEventRepository = mock(SecurityEventRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new SecurityMaintenanceService(
            refreshTokenRepository, securityEventRepository,
            new SecurityCleanupProperties(true, "0 0 3 * * *", 2, 30, 90),
            meterRegistry);
    }

    @Test
    void refreshCleanupPassesNowCutoffAndBatchSizeAndStopsOnPartialBatch() {
        when(refreshTokenRepository.deleteDeadTokensBatch(
                any(Instant.class), any(Instant.class), eq(2)))
            .thenReturn(2, 2, 1);

        int deleted = service.cleanupRefreshTokens();

        assertThat(deleted).isEqualTo(5);
        var nowCaptor = ArgumentCaptor.forClass(Instant.class);
        var cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokenRepository, org.mockito.Mockito.times(3))
            .deleteDeadTokensBatch(nowCaptor.capture(), cutoffCaptor.capture(), eq(2));
        // Cutoff must be exactly retention window behind 'now'.
        assertThat(java.time.Duration.between(
                cutoffCaptor.getValue(), nowCaptor.getValue()).toDays())
            .isEqualTo(30);
    }

    @Test
    void refreshCleanupNeverDeletesAnythingWhenNothingIsDead() {
        when(refreshTokenRepository.deleteDeadTokensBatch(
                any(Instant.class), any(Instant.class), anyInt()))
            .thenReturn(0);

        int deleted = service.cleanupRefreshTokens();

        assertThat(deleted).isZero();
        // Active tokens can never appear in the dead-row predicate; a zero
        // first batch proves no active row was touched.
        assertThat(meterRegistry.get("obd.security.cleanup.refresh.tokens.deleted")
            .counter().count()).isZero();
    }

    @Test
    void eventRetentionDeletesOnlyBeyondWindowInBatches() {
        when(securityEventRepository.deleteOlderThanBatch(any(Instant.class), eq(2)))
            .thenReturn(1);

        int deleted = service.cleanupSecurityEvents();

        assertThat(deleted).isEqualTo(1);
        var cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(securityEventRepository).deleteOlderThanBatch(cutoffCaptor.capture(), eq(2));
        assertThat(java.time.Duration.between(
                cutoffCaptor.getValue(), Instant.now()).toDays())
            .isEqualTo(90);
        assertThat(meterRegistry.get("obd.security.cleanup.security.events.deleted")
            .counter().count()).isEqualTo(1.0);
    }
}
