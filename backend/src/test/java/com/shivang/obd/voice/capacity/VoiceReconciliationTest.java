package com.shivang.obd.voice.capacity;

import static com.shivang.obd.voice.VoiceTestSupport.GATEWAY_A;
import static com.shivang.obd.voice.VoiceTestSupport.TENANT_A;
import static com.shivang.obd.voice.VoiceTestSupport.allocation;
import static com.shivang.obd.voice.VoiceTestSupport.gateway;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.telephony.SipGateway;
import com.shivang.obd.telephony.SipGatewayAllocation;
import com.shivang.obd.telephony.SipGatewayAllocationRepository;
import com.shivang.obd.telephony.SipGatewayRepository;
import com.shivang.obd.telephony.VoiceCapacityServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * VB-0 reconciliation tests (REC1–REC3).
 * <p>
 * The reconciliation SQL contract is: {@code UPDATE voice_channel_reservations
 * SET released_at = now() WHERE released_at IS NULL AND reserved_at < :cutoff}
 * with {@code cutoff = now() - 5 minutes}. These tests verify that the job
 * issues exactly that statement with that cutoff — i.e. (REC1) stale active
 * rows match, (REC2) recent active rows cannot match, (REC3) already-released
 * rows cannot match. The full database-level behavior is exercised in
 * VoiceReservationLifecycleIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class VoiceReconciliationTest {

    @Mock
    SipGatewayRepository gatewayRepository;

    @Mock
    SipGatewayAllocationRepository allocationRepository;

    @Mock
    EntityManager entityManager;

    VoiceCapacityServiceImpl service;

    final UUID gatewayId = GATEWAY_A;
    final UUID tenantId = TENANT_A;
    final UUID allocationId = UUID.fromString("aa000000-0000-4000-8000-00000000004a");

    @BeforeEach
    void setUp() throws Exception {
        service = new VoiceCapacityServiceImpl(gatewayRepository, allocationRepository);
        var field = VoiceCapacityServiceImpl.class.getDeclaredField("entityManager");
        field.setAccessible(true);
        field.set(service, entityManager);
    }

    private Query stubReconciliation(int updatedRows) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.executeUpdate()).thenReturn(updatedRows);
        when(entityManager.createNativeQuery(
                contains("UPDATE voice_channel_reservations SET released_at = now() WHERE released_at IS NULL AND reserved_at < :cutoff")))
                .thenReturn(q);
        return q;
    }

    @Test
    @org.junit.jupiter.api.DisplayName("REC1: stale reservation (older than cutoff, active) is released")
    void REC1_staleActiveReservation_isReleased() {
        // 3 stale reservations found by the predicate
        Query reconcile = stubReconciliation(3);

        service.reconcileStaleReservations();

        verify(reconcile).executeUpdate();
        verify(reconcile).setParameter(eq("cutoff"), any(Instant.class));
        // Predicate must exclude non-stale rows: reserved_at < cutoff AND released_at IS NULL
        verify(entityManager).createNativeQuery(
                argThat(sqlObj -> {
                    String sql = (String) sqlObj;
                    return sql.contains("released_at IS NULL")
                            && sql.contains("reserved_at < :cutoff");
                }));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("REC2: recent reservation (newer than cutoff) remains active")
    void REC2_recentActiveReservation_remainsActive() {
        // The cutoff excludes recent rows, so nothing matches.
        Query reconcile = stubReconciliation(0);

        service.reconcileStaleReservations();

        verify(reconcile).executeUpdate();
        verify(reconcile).setParameter(eq("cutoff"), any(Instant.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("REC3: already-released reservation is unchanged")
    void REC3_alreadyReleasedReservation_unchanged() {
        // The released_at IS NULL predicate excludes already-released rows.
        Query reconcile = stubReconciliation(0);

        service.reconcileStaleReservations();

        verify(reconcile).executeUpdate();
        verify(reconcile).setParameter(eq("cutoff"), any(Instant.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("reconciliation cutoff is approximately now() minus 5 minutes")
    void cutoff_isNowMinusFiveMinutes() {
        Query reconcile = stubReconciliation(0);

        service.reconcileStaleReservations();

        verify(reconcile).setParameter(eq("cutoff"), argThat((Object v) -> {
            Instant cutoff = (Instant) v;
            Instant expectedLow = Instant.now().minusSeconds(300 + 5); // 5 min tolerance
            Instant expectedHigh = Instant.now().minusSeconds(300 - 5);
            return cutoff.isAfter(expectedLow) && cutoff.isBefore(expectedHigh);
        }));
    }
}
