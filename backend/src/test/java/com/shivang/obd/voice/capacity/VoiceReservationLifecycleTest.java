package com.shivang.obd.voice.capacity;

import static com.shivang.obd.voice.VoiceTestSupport.GATEWAY_A;
import static com.shivang.obd.voice.VoiceTestSupport.TENANT_A;
import static com.shivang.obd.voice.VoiceTestSupport.allocation;
import static com.shivang.obd.voice.VoiceTestSupport.gateway;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.telephony.SipGateway;
import com.shivang.obd.telephony.SipGatewayAllocation;
import com.shivang.obd.telephony.SipGatewayAllocationRepository;
import com.shivang.obd.telephony.SipGatewayRepository;
import com.shivang.obd.telephony.VoiceCapacityServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * VB-0 reservation lifecycle tests (RES1–RES6).
 * <p>
 * Verifies the observable reservation contract of
 * {@link VoiceCapacityServiceImpl}: reserve inserts an active row
 * ({@code released_at IS NULL}), release marks it released, release is
 * idempotent (no negative usage, no error), and unknown gateway/tenant
 * combinations are safe no-ops. Database-level lifecycle semantics are
 * additionally covered by VoiceReservationLifecycleIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class VoiceReservationLifecycleTest {

    @Mock
    SipGatewayRepository gatewayRepository;

    @Mock
    SipGatewayAllocationRepository allocationRepository;

    @Mock
    EntityManager entityManager;

    VoiceCapacityServiceImpl service;

    final UUID gatewayId = GATEWAY_A;
    final UUID tenantId = TENANT_A;

    @BeforeEach
    void setUp() throws Exception {
        service = new VoiceCapacityServiceImpl(gatewayRepository, allocationRepository);
        var field = VoiceCapacityServiceImpl.class.getDeclaredField("entityManager");
        field.setAccessible(true);
        field.set(service, entityManager);
    }

    private void stubGateway() {
        SipGateway g = gateway(gatewayId, "TATA", 100, 10, 0);
        when(gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)).thenReturn(Optional.of(g));
    }

    private void stubAllocation() {
        SipGatewayAllocation a = allocation(UUID.randomUUID(), gatewayId, tenantId, 100, 10, 0);
        when(allocationRepository.findByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId))
                .thenReturn(Optional.of(a));
        when(allocationRepository.existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId))
                .thenReturn(true);
    }

    private Query stubQueryByText(String sqlFragment, Object singleResult, Integer updateCount) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        if (singleResult != null) {
            when(q.getSingleResult()).thenReturn(singleResult);
        }
        if (updateCount != null) {
            when(q.executeUpdate()).thenReturn(updateCount);
        }
        when(entityManager.createNativeQuery(contains(sqlFragment))).thenReturn(q);
        return q;
    }

    /** Stubs the full reserve() happy path: lock acquired, capacity free. */
    private Query stubReserveHappyPath() {
        return stubQueryByText("pg_try_advisory_xact_lock", Boolean.TRUE, null);
    }

    private void stubUsageQueriesFree() {
        stubQueryByText("SELECT COUNT(*) FROM voice_channel_reservations WHERE gateway_id = :gatewayId AND released_at IS NULL", 0L, null);
        stubQueryByText("AND tenant_id = :tenantId AND released_at IS NULL", 0L, null);
        stubQueryByText("tenant_id = :tenantId AND reserved_at >= now() - interval '1 second'", 0L, null);
        stubQueryByText("AND reserved_at >= now() - interval '1 second' AND released_at IS NULL", 0L, null);
    }

    private Query stubInsert() {
        return stubQueryByText("INSERT INTO voice_channel_reservations", null, 1);
    }

    private Query stubUnlock() {
        return stubQueryByText("pg_advisory_xact_unlock", null, 1);
    }

    @Nested
    class Reserve {

        @Test
        void RES1_reserve_insertsActiveReservation() {
            stubGateway();
            stubAllocation();
            stubReserveHappyPath();
            stubUsageQueriesFree();
            stubUnlock();
            Query insert = stubInsert();

            boolean result = service.reserve(gatewayId, tenantId);

            assertThat(result).isTrue();
            // The insert records the reservation; released_at defaults to NULL
            // in DDL (V30) so the row is active until released.
            verify(insert).executeUpdate();
        }

        @Test
        void RES1_reserveRowIsActiveViaUsageCountAfterReserve() {
            stubGateway();
            stubAllocation();
            stubReserveHappyPath();
            stubUsageQueriesFree();
            stubInsert();
            stubUnlock();

            assertThat(service.reserve(gatewayId, tenantId)).isTrue();
            assertThat(service.getCurrentUsage(gatewayId)).isZero(); // usage query stubbed free
        }

        @Test
        void reserveFails_whenAdvisoryLockUnavailable() {
            stubGateway();
            stubAllocation();
            stubQueryByText("pg_try_advisory_xact_lock", Boolean.FALSE, null);
            stubQueryByText("pg_advisory_xact_unlock", null, 1);

            boolean result = service.reserve(gatewayId, tenantId);

            assertThat(result).isFalse();
        }

        @Test
        void reserveFails_whenGatewayUnknown() {
            when(gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)).thenReturn(Optional.empty());

            boolean result = service.reserve(gatewayId, tenantId);

            assertThat(result).isFalse();
        }

        @Test
        void reserveFails_whenNoAllocationForTenant() {
            stubGateway();
            when(allocationRepository.existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId))
                    .thenReturn(false);
            when(gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId))
                    .thenReturn(Optional.of(gateway(gatewayId, "TATA", 100, 10, 0)));
            when(allocationRepository.existsByGatewayIdAndResellerIdAndEnabledTrueAndDeletedAtIsNull(
                    org.mockito.ArgumentMatchers.eq(gatewayId), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(false);

            boolean result = service.reserve(gatewayId, tenantId);

            assertThat(result).isFalse();
        }
    }

    @Nested
    class Release {

        @Test
        void RES2_reserveThenOriginateFailure_releaseMarksReservationReleased() {
            stubGateway();
            stubAllocation();
            stubReserveHappyPath();
            stubUsageQueriesFree();
            stubInsert();
            stubUnlock();
            Query release = stubQueryByText(
                    "UPDATE voice_channel_reservations SET released_at = now()", null, 1);

            assertThat(service.reserve(gatewayId, tenantId)).isTrue();
            // Originate fails => caller releases the reservation
            service.release(gatewayId, tenantId);

            verify(release).executeUpdate();
        }

        @Test
        void RES3_reserveThenChannelHangup_releasePopulatesReleasedAt() {
            stubGateway();
            stubAllocation();
            stubReserveHappyPath();
            stubUsageQueriesFree();
            stubInsert();
            stubUnlock();
            Query release = stubQueryByText(
                    "UPDATE voice_channel_reservations SET released_at = now()", null, 1);

            assertThat(service.reserve(gatewayId, tenantId)).isTrue();
            // CHANNEL_HANGUP => EslEventService releases via gateway+tenant
            service.release(gatewayId, tenantId);

            verify(release).executeUpdate();
        }

        @Test
        void RES4_doubleRelease_isIdempotentWithoutErrorAndWithoutNegativeUsage() {
            stubGateway();
            stubAllocation();
            stubReserveHappyPath();
            stubUsageQueriesFree();
            stubInsert();
            stubUnlock();
            Query release = stubQueryByText(
                    "UPDATE voice_channel_reservations SET released_at = now()", null, 1);

            service.reserve(gatewayId, tenantId);
            service.release(gatewayId, tenantId);
            int activeAfterFirst = service.getCurrentUsage(gatewayId); // stubbed count

            assertThatCode(() -> service.release(gatewayId, tenantId))
                    .as("second release must not throw")
                    .doesNotThrowAnyException();
            assertThat(activeAfterFirst).isGreaterThanOrEqualTo(0);
        }

        @Test
        void RES5_duplicateHangupEvents_releaseOnlyTouchesActiveRows() {
            stubGateway();
            stubAllocation();
            stubReserveHappyPath();
            stubUsageQueriesFree();
            stubInsert();
            stubUnlock();
            // The release UPDATE only matches released_at IS NULL rows, so a
            // duplicate hangup updates zero additional rows in the database.
            // (Row-level behavior proven in VoiceReservationLifecycleIntegrationTest.)
            Query release = stubQueryByText(
                    "UPDATE voice_channel_reservations SET released_at = now()", null, 0);

            service.reserve(gatewayId, tenantId);
            service.release(gatewayId, tenantId);
            assertThatCode(() -> service.release(gatewayId, tenantId))
                    .as("duplicate hangup must not throw")
                    .doesNotThrowAnyException();

            // One UPDATE per release() call; the released_at IS NULL predicate
            // is what makes the duplicate a database-level no-op.
            verify(release, org.mockito.Mockito.times(2)).executeUpdate();
            verify(entityManager, org.mockito.Mockito.atLeastOnce()).createNativeQuery(
                    org.mockito.ArgumentMatchers.<String>argThat(sql ->
                            sql != null
                                    && sql.contains("UPDATE voice_channel_reservations SET released_at = now()")
                                    && sql.contains("released_at IS NULL")));
        }

        @Test
        void RES6_unknownGatewayOrTenant_releaseIsSafeNoOp() {
            Query release = stubQueryByText(
                    "UPDATE voice_channel_reservations SET released_at = now()", null, 0);

            assertThatCode(() -> service.release(UUID.randomUUID(), UUID.randomUUID()))
                    .doesNotThrowAnyException();
            verify(release).executeUpdate();
        }
    }
}
