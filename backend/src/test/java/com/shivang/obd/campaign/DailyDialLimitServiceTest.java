package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VB-6C.1 unit tests: effective-limit semantics, bucket-key/timezone
 * derivation, and the deterministic invalid-timezone failure. Ledger
 * persistence/concurrency is proven separately against real PostgreSQL
 * ({@code VoiceBlastDailyDialLimitPostgresIntegrationTest}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailyDialLimitServiceTest {

    private static final UUID TENANT =
            UUID.fromString("dd000000-0000-4000-8000-00000000000a");
    private static final UUID CONTACT =
            UUID.fromString("dd000000-0000-4000-8000-00000000000c");
    private static final UUID DID =
            UUID.fromString("dd000000-0000-4000-8000-00000000000d");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 27);

    @Mock
    private VoiceBlastDailyUsageRepository usageRepository;
    @Mock
    private VoiceBlastDailyUsageEntryRepository entryRepository;

    /** Clock pinned to 2026-09-27T20:00Z — same UTC day, next Kolkata day. */
    private static final Clock PINNED =
            Clock.fixed(Instant.parse("2026-09-27T20:00:00Z"), ZoneId.of("UTC"));

    private DailyDialLimitService service() {
        return new DailyDialLimitService(usageRepository, entryRepository,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), PINNED);
    }

    @Nested
    class EffectiveLimit {

        @Test
        @DisplayName("effectiveLimit = 3 when no campaign limit is configured")
        void platformMaxWhenUnconfigured() {
            assertThat(service().effectiveLimit(null)).isEqualTo(3);
        }

        @Test
        @DisplayName("effectiveLimit is the campaign value when below the platform max")
        void campaignValueBelowMax() {
            assertThat(service().effectiveLimit(1)).isEqualTo(1);
            assertThat(service().effectiveLimit(2)).isEqualTo(2);
        }

        @Test
        @DisplayName("effectiveLimit can never exceed the platform max of 3")
        void cannotExceedPlatformMax() {
            assertThat(service().effectiveLimit(3)).isEqualTo(3);
            assertThat(service().effectiveLimit(5)).isEqualTo(3);
            assertThat(service().effectiveLimit(100)).isEqualTo(3);
        }
    }

    @Nested
    class Timezone {

        @Test
        @DisplayName("usage date is the calendar day in the snapshot IANA timezone")
        void usageDateInSnapshotZone() {
            // 18:00Z = 2026-09-27 in UTC but 2026-09-28 in Asia/Kolkata (+05:30).
            assertThat(service().resolveUsageDate("UTC")).isEqualTo(DAY);
            assertThat(service().resolveUsageDate("Asia/Kolkata"))
                    .isEqualTo(LocalDate.of(2026, 9, 28));
        }

        @Test
        @DisplayName("null, blank and invalid timezones fail deterministically — no JVM/UTC fallback")
        void invalidTimezoneFailsClosed() {
            DailyDialLimitService svc = service();
            assertThatThrownBy(() -> svc.resolveUsageDate(null))
                    .isInstanceOf(ExecutionTimezoneInvalidException.class);
            assertThatThrownBy(() -> svc.resolveUsageDate(""))
                    .isInstanceOf(ExecutionTimezoneInvalidException.class);
            assertThatThrownBy(() -> svc.resolveUsageDate("   "))
                    .isInstanceOf(ExecutionTimezoneInvalidException.class);
            assertThatThrownBy(() -> svc.resolveUsageDate("Not/AZone"))
                    .isInstanceOf(ExecutionTimezoneInvalidException.class);
        }
    }

    @Nested
    class Admission {

        @Test
        @DisplayName("admit creates the bucket row when absent, then reserves")
        void admitCreatesBucketThenReserves() {
            org.mockito.Mockito.when(usageRepository.reserve(
                    TENANT, CONTACT, DID, DAY, 3)).thenReturn(1);

            DailyDialLimitService.AdmissionResult result = service().admit(
                    TENANT, CONTACT, DID, DAY, 3);

            assertThat(result).isEqualTo(DailyDialLimitService.AdmissionResult.ADMITTED);
            // Idempotent INSERT … ON CONFLICT DO NOTHING precedes the reserve.
            org.mockito.Mockito.verify(usageRepository).insertBucketRow(TENANT, CONTACT, DID, DAY);
            org.mockito.Mockito.verify(usageRepository).reserve(TENANT, CONTACT, DID, DAY, 3);
        }

        @Test
        @DisplayName("admit returns DAILY_LIMIT_REACHED when the conditional reserve updates 0 rows")
        void admitRejectedWhenBucketExhausted() {
            org.mockito.Mockito.when(usageRepository.reserve(
                    TENANT, CONTACT, DID, DAY, 3)).thenReturn(0);

            DailyDialLimitService.AdmissionResult result = service().admit(
                    TENANT, CONTACT, DID, DAY, 3);

            assertThat(result).isEqualTo(DailyDialLimitService.AdmissionResult.DAILY_LIMIT_REACHED);
        }

        @Test
        @DisplayName("confirmAccepted inserts one entry and increments usage")
        void confirmWritesEntryAndIncrement() {
            service().confirmAccepted(TENANT,
                    UUID.fromString("dd000000-0000-4000-8000-0000000000a1"),
                    CONTACT, DID, DAY, "fs-uuid-1");

            org.mockito.Mockito.verify(usageRepository).confirmUsed(TENANT, CONTACT, DID, DAY);
            org.mockito.Mockito.verify(entryRepository).saveAndFlush(
                    org.mockito.ArgumentMatchers.any(VoiceBlastDailyUsageEntry.class));
        }

        @Test
        @DisplayName("duplicate confirmation (unique key violation) is suppressed, not rethrown")
        void duplicateConfirmationSuppressed() {
            org.mockito.Mockito.when(entryRepository.saveAndFlush(
                    org.mockito.ArgumentMatchers.any(VoiceBlastDailyUsageEntry.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "uq_vbdue_call_attempt"));

            service().confirmAccepted(TENANT,
                    UUID.fromString("dd000000-0000-4000-8000-0000000000a2"),
                    CONTACT, DID, DAY, "fs-uuid-2");

            // No exception propagated; the bucket increment was attempted
            // exactly once (the replay is neutralized at the entry insert).
            org.mockito.Mockito.verify(usageRepository, org.mockito.Mockito.times(1))
                    .confirmUsed(TENANT, CONTACT, DID, DAY);
        }
    }

    @Nested
    class Isolation {

        @Test
        @DisplayName("bucket key passes tenant, contact and DID through untouched — no cross-tenant bleed")
        void bucketKeyIsFullyParameterized() {
            UUID otherTenant = UUID.randomUUID();
            UUID otherContact = UUID.randomUUID();
            UUID otherDid = UUID.randomUUID();

            service().admit(otherTenant, otherContact, otherDid, DAY, 3);

            // The ledger operations carry ALL key components — tenant is
            // part of every statement, never implied by context.
            org.mockito.Mockito.verify(usageRepository).insertBucketRow(
                    otherTenant, otherContact, otherDid, DAY);
            org.mockito.Mockito.verify(usageRepository).reserve(
                    otherTenant, otherContact, otherDid, DAY, 3);
        }
    }
}
