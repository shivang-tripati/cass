package com.shivang.obd.voice.capacity;

import static com.shivang.obd.voice.VoiceTestSupport.GATEWAY_A;
import static com.shivang.obd.voice.VoiceTestSupport.TENANT_A;
import static com.shivang.obd.voice.VoiceTestSupport.allocation;
import static com.shivang.obd.voice.VoiceTestSupport.gateway;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.telephony.SipGateway;
import com.shivang.obd.telephony.SipGatewayAllocation;
import com.shivang.obd.telephony.SipGatewayAllocationRepository;
import com.shivang.obd.telephony.SipGatewayRepository;
import com.shivang.obd.telephony.SipGatewayStatus;
import com.shivang.obd.telephony.VoiceCapacityServiceImpl;
import com.shivang.obd.voice.capacity.VoiceCapacityService.CapacityCheckResult;
import com.shivang.obd.voice.routing.VoiceRoutingReason;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * VB-0 capacity behavior tests (C1–C8).
 * <p>
 * Unit-level behavior tests for {@link VoiceCapacityServiceImpl}.
 * Concurrency behavior is covered separately against real PostgreSQL
 * (VoiceCapacityConcurrencyIntegrationTest) — the advisory lock is never
 * mocked for that purpose. Here the native usage queries are stubbed to
 * simulate occupied capacity, including the CPS 1-second window (the SQL
 * predicate {@code reserved_at >= now() - interval '1 second'} is part of
 * the query string; the test verifies the impl's decisions against those
 * simulated window counts, not wall-clock timing).
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class VoiceCapacityServiceTest {

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

    private void stubGateway(int maxChannels, Integer maxCps, int headroomPct) {
        SipGateway g = gateway(gatewayId, "TATA", maxChannels, maxCps, headroomPct);
        g.setStatus(SipGatewayStatus.ACTIVE);
        g.setEnabled(true);
        when(gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)).thenReturn(Optional.of(g));
    }

    private void stubTenantAllocation(int maxChannels, Integer maxCps, int headroomPct) {
        SipGatewayAllocation a = allocation(UUID.randomUUID(), gatewayId, tenantId, maxChannels, maxCps, headroomPct);
        when(allocationRepository.findByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId))
                .thenReturn(Optional.of(a));
        when(allocationRepository.existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId))
                .thenReturn(true);
    }

    private void stubNoAllocation() {
        when(allocationRepository.findByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId))
                .thenReturn(Optional.empty());
        when(allocationRepository.existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId))
                .thenReturn(false);
    }

    /** Single stubbed query returning the given value from getSingleResult. */
    private Query stubQueryReturning(Object value) {
        Query q = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(q);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        if (value != null) {
            when(q.getSingleResult()).thenReturn(value);
        }
        return q;
    }

    /** Stub that distinguishes usage-count queries by their SQL text. */
    private UsageStubs stubUsageQueries() {
        return new UsageStubs();
    }

    private final class UsageStubs {
        Query channelUsage;   // "released_at IS NULL" without tenant filter
        Query tenantUsage;    // tenant-filtered channel usage
        Query cpsUsage;       // 1-second window, gateway-wide
        Query tenantCpsUsage; // 1-second window, tenant-filtered
        Query lockQuery;
        Query insertQuery;
        Query unlockQuery;

        UsageStubs() {
            lockQuery = mock(Query.class);
            when(lockQuery.setParameter(anyString(), any())).thenReturn(lockQuery);
            when(lockQuery.getSingleResult()).thenReturn(Boolean.TRUE);

            insertQuery = mock(Query.class);
            when(insertQuery.setParameter(anyString(), any())).thenReturn(insertQuery);
            when(insertQuery.executeUpdate()).thenReturn(1);

            unlockQuery = mock(Query.class);
            when(unlockQuery.setParameter(anyString(), any())).thenReturn(unlockQuery);
            when(unlockQuery.executeUpdate()).thenReturn(1);

            when(entityManager.createNativeQuery(contains("pg_try_advisory_xact_lock")))
                    .thenReturn(lockQuery);
            when(entityManager.createNativeQuery(contains("INSERT INTO voice_channel_reservations")))
                    .thenReturn(insertQuery);
            when(entityManager.createNativeQuery(contains("pg_advisory_xact_unlock")))
                    .thenReturn(unlockQuery);
        }

        void channelUsage(int count) {
            channelUsage = mock(Query.class);
            when(channelUsage.setParameter(anyString(), any())).thenReturn(channelUsage);
            when(channelUsage.getSingleResult()).thenReturn(count);
            when(entityManager.createNativeQuery(
                    contains("SELECT COUNT(*) FROM voice_channel_reservations WHERE gateway_id = :gatewayId AND released_at IS NULL")))
                    .thenReturn(channelUsage);
        }

        void tenantUsage(int count) {
            tenantUsage = mock(Query.class);
            when(tenantUsage.setParameter(anyString(), any())).thenReturn(tenantUsage);
            when(tenantUsage.getSingleResult()).thenReturn(count);
            when(entityManager.createNativeQuery(
                    contains("AND tenant_id = :tenantId AND released_at IS NULL")))
                    .thenReturn(tenantUsage);
        }

        void cpsUsage(int count) {
            cpsUsage = mock(Query.class);
            when(cpsUsage.setParameter(anyString(), any())).thenReturn(cpsUsage);
            when(cpsUsage.getSingleResult()).thenReturn(count);
            when(entityManager.createNativeQuery(
                    contains("AND reserved_at >= now() - interval '1 second' AND released_at IS NULL")))
                    .thenReturn(cpsUsage);
        }

        void tenantCpsUsage(int count) {
            tenantCpsUsage = mock(Query.class);
            when(tenantCpsUsage.setParameter(anyString(), any())).thenReturn(tenantCpsUsage);
            when(tenantCpsUsage.getSingleResult()).thenReturn(count);
            // tenant CPS query is the more specific string, must be registered FIRST
            when(entityManager.createNativeQuery(
                    contains("tenant_id = :tenantId AND reserved_at >= now() - interval '1 second' AND released_at IS NULL")))
                    .thenReturn(tenantCpsUsage);
        }
    }

    @Nested
    class ChannelCapacity {

        @Test
        void C1_tenReservationsAdmittedAtLimit10_eleventhRejected() {
            stubGateway(10, 10, 0);
            stubTenantAllocation(10, 10, 0);
            UsageStubs s = stubUsageQueries();

            // reservations 1..10: gateway usage 0..9, tenant usage equals gateway usage
            for (int used = 0; used < 10; used++) {
                s.channelUsage(used);
                s.tenantUsage(used);
                s.cpsUsage(0);
                s.tenantCpsUsage(0);

                CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
                assertThat(r.available()).as("admission %d of 10", used + 1).isTrue();
            }

            // 11th: at capacity
            s.channelUsage(10);
            s.tenantUsage(10);
            s.cpsUsage(0);
            s.tenantCpsUsage(0);

            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode());
        }

        @Test
        void C1_reserveReturnsFalseWhenGatewayCapacityFull() {
            stubGateway(10, 10, 0);
            stubTenantAllocation(10, 10, 0);
            UsageStubs s = stubUsageQueries();
            s.channelUsage(10); // full
            s.tenantUsage(10);
            s.cpsUsage(0);
            s.tenantCpsUsage(0);

            boolean reserved = service.reserve(gatewayId, tenantId);

            assertThat(reserved).isFalse();
        }
    }

    @Nested
    class Headroom {

        @Test
        void C2_gateway100ChannelsWith10PercentHeadroom_effectiveCapacity90() {
            stubGateway(100, 100, 10); // 10% headroom => 90 effective
            stubTenantAllocation(100, 100, 0);
            UsageStubs s = stubUsageQueries();

            for (int used = 0; used < 90; used++) {
                s.channelUsage(used);
                s.tenantUsage(used);
                s.cpsUsage(0);
                s.tenantCpsUsage(0);
                assertThat(service.checkCapacity(gatewayId, tenantId).available())
                        .as("admission %d of 90", used + 1).isTrue();
            }

            s.channelUsage(90);
            s.tenantUsage(90);
            s.cpsUsage(0);
            s.tenantCpsUsage(0);
            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode());
        }

        @Test
        void C4_allocation50ChannelsWith20PercentHeadroom_effectiveAllocationCapacity40() {
            stubGateway(100, 100, 0);
            stubTenantAllocation(50, 100, 20); // 20% headroom => 40 effective
            UsageStubs s = stubUsageQueries();

            // Gateway-level usage stays below gateway limit; allocation limit binds.
            for (int tenantUsed = 0; tenantUsed < 40; tenantUsed++) {
                s.channelUsage(10); // gateway-wide usage low
                s.tenantUsage(tenantUsed);
                s.cpsUsage(0);
                s.tenantCpsUsage(0);
                assertThat(service.checkCapacity(gatewayId, tenantId).available())
                        .as("allocation admission %d of 40", tenantUsed + 1).isTrue();
            }

            s.channelUsage(10);
            s.tenantUsage(40);
            s.cpsUsage(0);
            s.tenantCpsUsage(0);
            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode());
        }

        @Test
        void C5_gatewayEffective90_allocationEffective40_effectiveMinimumIs40() {
            stubGateway(100, 100, 10);   // gateway effective = 90
            stubTenantAllocation(50, 100, 20); // allocation effective = 40
            UsageStubs s = stubUsageQueries();

            for (int tenantUsed = 0; tenantUsed < 40; tenantUsed++) {
                s.channelUsage(10); // gateway-wide usage (10 < 90: gateway not binding)
                s.tenantUsage(tenantUsed);
                s.cpsUsage(0);
                s.tenantCpsUsage(0);
                assertThat(service.checkCapacity(gatewayId, tenantId).available())
                        .as("admission %d of 40 (allocation-bound)", tenantUsed + 1).isTrue();
            }

            s.channelUsage(10);
            s.tenantUsage(40);
            s.cpsUsage(0);
            s.tenantCpsUsage(0);
            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode());
        }

        @Test
        void getMaxCapacity_reflectsGatewayHeadroom() {
            stubGateway(100, 10, 10);
            assertThat(service.getMaxCapacity(gatewayId)).isEqualTo(90);
        }
    }

    @Nested
    class AllocationLimits {

        @Test
        void C3_allocation20Channels_capsTenantUsageAt20_evenWhenGatewayHasCapacity() {
            stubGateway(100, 100, 0);
            stubTenantAllocation(20, 100, 0);
            UsageStubs s = stubUsageQueries();

            for (int tenantUsed = 0; tenantUsed < 20; tenantUsed++) {
                s.channelUsage(50); // gateway-wide usage high but below gateway limit
                s.tenantUsage(tenantUsed);
                s.cpsUsage(0);
                s.tenantCpsUsage(0);
                assertThat(service.checkCapacity(gatewayId, tenantId).available())
                        .as("tenant admission %d of 20", tenantUsed + 1).isTrue();
            }

            s.channelUsage(50);
            s.tenantUsage(20);
            s.cpsUsage(0);
            s.tenantCpsUsage(0);
            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode());
        }
    }

    @Nested
    class Cps {

        @Test
        void C6_moreThan5AdmissionsInOneSecondWindow_rejectedWithCpsReason() {
            stubGateway(100, 5, 0); // gateway CPS = 5
            stubTenantAllocation(100, 5, 0);
            UsageStubs s = stubUsageQueries();

            // Window count 0..4 => admissions 1..5 succeed
            for (int cps = 0; cps < 5; cps++) {
                s.channelUsage(0);
                s.tenantUsage(0);
                s.cpsUsage(cps);
                s.tenantCpsUsage(cps);
                CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
                assertThat(r.available()).as("CPS admission %d of 5", cps + 1).isTrue();
            }

            // Window count 5 => 6th admission in the same 1-second window rejected
            s.channelUsage(0);
            s.tenantUsage(0);
            s.cpsUsage(5);
            s.tenantCpsUsage(5);
            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CPS_CAPACITY.getCode());
        }

        @Test
        void C7_gatewayCps10_allocationCps2_allocationCpsCannotExceed2() {
            stubGateway(100, 10, 0);
            stubTenantAllocation(100, 2, 0); // allocation CPS = 2
            UsageStubs s = stubUsageQueries();

            for (int cps = 0; cps < 2; cps++) {
                s.channelUsage(0);
                s.tenantUsage(0);
                s.cpsUsage(cps);
                s.tenantCpsUsage(cps);
                assertThat(service.checkCapacity(gatewayId, tenantId).available())
                        .as("allocation CPS admission %d of 2", cps + 1).isTrue();
            }

            s.channelUsage(0);
            s.tenantUsage(0);
            s.cpsUsage(2); // gateway CPS not exhausted (2 < 10)
            s.tenantCpsUsage(2); // allocation CPS exhausted
            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CPS_CAPACITY.getCode());
        }
    }

    @Nested
    class ChannelVsCps {

        @Test
        void C8_caseA_channelsExhaustedCpsAvailable_rejectedWithChannelReason() {
            stubGateway(10, 10, 0);
            stubTenantAllocation(10, 10, 0);
            UsageStubs s = stubUsageQueries();
            s.channelUsage(10); // full
            s.tenantUsage(10);
            s.cpsUsage(0);      // CPS free
            s.tenantCpsUsage(0);

            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode());
        }

        @Test
        void C8_caseB_channelsAvailableCpsExhausted_rejectedWithCpsReason() {
            stubGateway(100, 5, 0);
            stubTenantAllocation(100, 5, 0);
            UsageStubs s = stubUsageQueries();
            s.channelUsage(10); // channels free
            s.tenantUsage(10);
            s.cpsUsage(5);      // CPS at limit
            s.tenantCpsUsage(5);

            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CPS_CAPACITY.getCode());
        }

        @Test
        void C8_reasonsAreDistinctBetweenChannelAndCpsExhaustion() {
            stubGateway(10, 5, 0);
            stubTenantAllocation(10, 5, 0);
            UsageStubs s = stubUsageQueries();

            s.channelUsage(10);
            s.tenantUsage(10);
            s.cpsUsage(0);
            s.tenantCpsUsage(0);
            String channelReason = service.checkCapacity(gatewayId, tenantId).rejectionReason();

            s.channelUsage(0);
            s.tenantUsage(0);
            s.cpsUsage(5);
            s.tenantCpsUsage(5);
            String cpsReason = service.checkCapacity(gatewayId, tenantId).rejectionReason();

            assertThat(channelReason)
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode());
            assertThat(cpsReason)
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CPS_CAPACITY.getCode());
            assertThat(channelReason).isNotEqualTo(cpsReason);
        }
    }

    @Nested
    class Authorization {

        @Test
        void P3_gatewayWithoutTenantAllocation_isRejectedAsUnauthorized() {
            stubGateway(100, 10, 0);
            stubNoAllocation();
            stubUsageQueries();

            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_TENANT_NOT_AUTHORIZED.getCode());
        }

        @Test
        @DisplayName("null allocation max_concurrent_channels means no allocation-level limit (defensive)")
        void nullAllocationChannels_doNotCrashAndDoNotLimit() {
            stubGateway(10, 10, 0);
            SipGatewayAllocation a = allocation(UUID.randomUUID(), gatewayId, tenantId);
            a.setMaxConcurrentChannels(null); // DDL: NULL = no limit
            when(allocationRepository.findByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId))
                    .thenReturn(Optional.of(a));
            when(allocationRepository.existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId))
                    .thenReturn(true);

            UsageStubs s = stubUsageQueries();
            s.channelUsage(0);
            s.tenantUsage(0);
            s.cpsUsage(0);
            s.tenantCpsUsage(0);

            assertThat(service.checkCapacity(gatewayId, tenantId).available()).isTrue();
        }
    }

    @Nested
    class GatewayState {

        @Test
        void inactiveGateway_rejectedWithGatewayInactiveReason() {
            SipGateway g = gateway(gatewayId, "TATA", 100, 10, 0);
            g.setStatus(SipGatewayStatus.INACTIVE);
            when(gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)).thenReturn(Optional.of(g));

            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_INACTIVE.getCode());
        }

        @Test
        void degradedGateway_rejectedWithGatewayDegradedReason() {
            SipGateway g = gateway(gatewayId, "TATA", 100, 10, 0);
            g.setStatus(SipGatewayStatus.DEGRADED);
            when(gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)).thenReturn(Optional.of(g));

            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_DEGRADED.getCode());
        }

        @Test
        void disabledGateway_rejectedWithGatewayDisabledReason() {
            SipGateway g = gateway(gatewayId, "TATA", 100, 10, 0);
            g.setEnabled(false);
            when(gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)).thenReturn(Optional.of(g));

            CapacityCheckResult r = service.checkCapacity(gatewayId, tenantId);
            assertThat(r.available()).isFalse();
            assertThat(r.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_DISABLED.getCode());
        }
    }
}
