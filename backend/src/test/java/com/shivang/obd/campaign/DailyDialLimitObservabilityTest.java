package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * VB-6C.3 — operational visibility for the Voice Blast daily dial limit.
 * <p>
 * Asserts the three signals this phase adds, and nothing about the business
 * rule (which VB-6C.1/6C.2 already prove elsewhere):
 * <ul>
 *   <li>{@code obd.campaign.voiceblast.dailylimit.reached} increments exactly
 *       once per refused admission, tagged only with the bounded
 *       {@code effectiveLimit};</li>
 *   <li>{@code obd.campaign.voiceblast.execution.timezone.invalid} increments
 *       once per unusable snapshot timezone (missing and malformed alike),
 *       and never carries the offending value as a tag;</li>
 *   <li>{@code obd.campaign.voiceblast.dailylimit.reserved.buckets} is
 *       registered as a gauge reading the repository's stranded-hold count.</li>
 * </ul>
 * The central anti-requirement: no unbounded label may ever appear on these
 * meters — that is the property that keeps the signal safe to aggregate, and
 * it is asserted explicitly rather than assumed.
 */
class DailyDialLimitObservabilityTest {

    private static final Clock PINNED =
            Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneId.of("UTC"));

    private VoiceBlastDailyUsageRepository usageRepository;
    private VoiceBlastDailyUsageEntryRepository entryRepository;
    private SimpleMeterRegistry registry;
    private DailyDialLimitService service;

    private final UUID tenantId = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");
    private final UUID contactId = UUID.fromString("cccccccc-0000-4000-8000-000000000001");
    private final UUID didId = UUID.fromString("dddddddd-0000-4000-8000-000000000001");
    private final LocalDate usageDate = LocalDate.of(2026, 9, 27);

    @BeforeEach
    void setUp() {
        usageRepository = Mockito.mock(VoiceBlastDailyUsageRepository.class);
        entryRepository = Mockito.mock(VoiceBlastDailyUsageEntryRepository.class);
        registry = new SimpleMeterRegistry();
        service = new DailyDialLimitService(
                usageRepository, entryRepository, registry, PINNED);
    }

    private double counterValue(String name, String... tagKeyValues) {
        Meter meter = registry.find(name).tags(tagKeyValues).meter();
        assertThat(meter)
                .as("meter %s%s should be registered", name,
                        tagKeyValues.length == 0 ? "" : " " + java.util.Arrays.toString(tagKeyValues))
                .isNotNull();
        return meter.measure().iterator().next().getValue();
    }

    @Nested
    class DailyLimitReached {

        @Test
        @DisplayName("OBS-1: a refused admission increments the counter exactly once")
        void refusalIncrementsOnce() {
            Mockito.when(usageRepository.reserve(
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.anyInt())).thenReturn(0);

            assertThat(service.admit(tenantId, contactId, didId, usageDate, 3))
                    .isEqualTo(DailyDialLimitService.AdmissionResult.DAILY_LIMIT_REACHED);

            assertThat(counterValue(DailyDialLimitService.METRIC_DAILY_LIMIT_REACHED,
                    "effectiveLimit", "3")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("OBS-2: an admitted dial does NOT increment the counter")
        void admissionDoesNotIncrement() {
            Mockito.when(usageRepository.reserve(
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.anyInt())).thenReturn(1);

            assertThat(service.admit(tenantId, contactId, didId, usageDate, 3))
                    .isEqualTo(DailyDialLimitService.AdmissionResult.ADMITTED);

            assertThat(registry.find(DailyDialLimitService.METRIC_DAILY_LIMIT_REACHED)
                    .meter()).isNull();
        }

        @Test
        @DisplayName("OBS-3: the effective limit is reported as a bounded 1..3 tag")
        void effectiveLimitIsTagged() {
            for (int limit : new int[] {1, 2, 3}) {
                SimpleMeterRegistry local = new SimpleMeterRegistry();
                DailyDialLimitService svc = new DailyDialLimitService(
                        usageRepository, entryRepository, local, PINNED);
                Mockito.when(usageRepository.reserve(
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyInt())).thenReturn(0);

                svc.admit(tenantId, contactId, didId, usageDate, limit);

                assertThat(local.find(DailyDialLimitService.METRIC_DAILY_LIMIT_REACHED)
                        .tag("effectiveLimit", String.valueOf(limit)).counter().count())
                        .isEqualTo(1.0);
            }
        }
    }

    @Nested
    class TimezoneInvalid {

        @Test
        @DisplayName("OBS-4: a missing snapshot timezone counts once and throws")
        void missingTimezoneCounts() {
            assertThatThrownBy(() -> service.resolveUsageDate(null))
                    .isInstanceOf(ExecutionTimezoneInvalidException.class);
            assertThatThrownBy(() -> service.resolveUsageDate("   "))
                    .isInstanceOf(ExecutionTimezoneInvalidException.class);

            assertThat(counterValue(DailyDialLimitService.METRIC_TIMEZONE_INVALID))
                    .isEqualTo(2.0);
        }

        @Test
        @DisplayName("OBS-5: a malformed snapshot timezone counts once and throws")
        void malformedTimezoneCounts() {
            assertThatThrownBy(() -> service.resolveUsageDate("Mars/Olympus_Mons"))
                    .isInstanceOf(ExecutionTimezoneInvalidException.class);

            assertThat(counterValue(DailyDialLimitService.METRIC_TIMEZONE_INVALID))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("OBS-6: a valid timezone counts nothing and resolves the day")
        void validTimezoneIsNotCounted() {
            assertThat(service.resolveUsageDate("Asia/Kolkata")).isEqualTo(usageDate);

            assertThat(registry.find(DailyDialLimitService.METRIC_TIMEZONE_INVALID).meter())
                    .isNull();
        }
    }

    @Nested
    class LedgerVisibility {

        @Test
        @DisplayName("OBS-7: the stranded-hold gauge is registered and reads the repository")
        void gaugeReadsRepository() {
            Mockito.when(usageRepository.countBucketsWithReservations()).thenReturn(4L);

            assertThat(counterValue(DailyDialLimitService.METRIC_RESERVED_BUCKETS))
                    .isEqualTo(4.0);
        }
    }

    @Test
    @DisplayName("OBS-8: no metric label carries an unbounded identifier")
    void noHighCardinalityLabels() {
        Mockito.when(usageRepository.reserve(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyInt())).thenReturn(0);
        service.admit(tenantId, contactId, didId, usageDate, 2);
        assertThatThrownBy(() -> service.resolveUsageDate(null))
                .isInstanceOf(ExecutionTimezoneInvalidException.class);

        // Every tag key across every meter this phase registers must be
        // bounded — an operator can group by these safely forever.
        var forbidden = java.util.List.of(
                "tenant", "tenantId", "campaign", "campaignId", "contact", "contactId",
                "did", "didId", "phone", "phoneNumber", "e164", "attempt", "attemptId",
                "execution", "executionId", "providerCallId", "timezone", "date",
                "usageDate", "snakeCase_mix");
        assertThat(registry.getMeters())
                .isNotEmpty()
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(io.micrometer.core.instrument.Tag::getKey)
                        .doesNotContainAnyElementsOf(forbidden));

        // Only two meters carry tags at all, and the sole value used is the
        // bounded effective limit — never an id.
        assertThat(registry.getMeters())
                .filteredOn(m -> !m.getId().getTags().isEmpty())
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getId().getName())
                            .isEqualTo(DailyDialLimitService.METRIC_DAILY_LIMIT_REACHED);
                    assertThat(m.getId().getTags())
                            .extracting(io.micrometer.core.instrument.Tag::getValue)
                            .containsExactly("2");
                });
    }
}
