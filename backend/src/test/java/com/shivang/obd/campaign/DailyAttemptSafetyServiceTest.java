package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.common.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * VB-6D.3 — the campaign daily-attempt safety policy in isolation.
 *
 * <p>Deliberately separated from persistence: this suite pins the
 * <em>semantics</em> (effective limit, ceiling, validation, and the fact that
 * a rejection is terminal for the day) using a mocked repository, while
 * {@code DailyAttemptConcurrencyPostgresIntegrationTest} proves the same
 * admission is genuinely atomic against real PostgreSQL. Neither suite alone
 * would be sufficient: this one cannot prove atomicity, and the other cannot
 * conveniently enumerate every configuration.
 */
class DailyAttemptSafetyServiceTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");
    private static final UUID CONTACT = UUID.fromString("cccccccc-0000-4000-8000-000000000001");
    private static final String TZ = "Asia/Kolkata";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 27);

    private VoiceBlastDailyAttemptRepository repository;
    private DailyDialLimitService dialLimitService;
    private DailyAttemptSafetyService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(VoiceBlastDailyAttemptRepository.class);
        dialLimitService = Mockito.mock(DailyDialLimitService.class);
        Mockito.lenient().when(dialLimitService.resolveUsageDate(Mockito.any()))
                .thenReturn(DAY);
        service = new DailyAttemptSafetyService(
                repository, dialLimitService, new SimpleMeterRegistry());
    }

    private void grants(int rows) {
        Mockito.when(repository.reserve(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyInt())).thenReturn(rows);
    }

    @Nested
    class EffectiveLimit {

        @Test
        @DisplayName("DA-1: no campaign override means the platform default")
        void nullMeansPlatformDefault() {
            assertThat(service.effectiveLimit(null))
                    .isEqualTo(DailyAttemptSafetyService.MAX_DAILY_ATTEMPTS_PER_CONTACT);
        }

        @ParameterizedTest(name = "DA-2: campaign limit {0} is honoured")
        @ValueSource(ints = {1, 2, 5, 9})
        @DisplayName("DA-2: a stricter campaign ceiling is used verbatim")
        void campaignCeilingHonoured(int configured) {
            assertThat(service.effectiveLimit(configured)).isEqualTo(configured);
        }

        @Test
        @DisplayName("DA-3: a campaign can never exceed the platform maximum")
        void campaignCannotExceedPlatform() {
            // Belt and braces: validation already rejects this, and the DB
            // CHECK refuses to store it, so this guard is the third layer.
            for (int tooHigh : new int[] {11, 50, 1000}) {
                assertThat(service.effectiveLimit(tooHigh))
                        .isEqualTo(DailyAttemptSafetyService.MAX_DAILY_ATTEMPTS_PER_CONTACT);
            }
        }

        @Test
        @DisplayName("DA-4: the platform default matches the repository's existing attempt bound")
        void platformDefaultIsEvidenceBased() {
            // The platform ceiling reuses the repository's own established
            // per-contact attempt bound (retry_max_attempts is 0..10 in the V14
            // CHECK and in the DTO, and RetryRule.MAX_RETRIES is 10), so a
            // contact is never dialled more often in a day than the platform
            // already considers sane for a single execution.
            assertThat(DailyAttemptSafetyService.MAX_DAILY_ATTEMPTS_PER_CONTACT)
                    .isEqualTo(RetryRule.MAX_RETRIES)
                    .isEqualTo(10);
        }
    }

    @Nested
    class Validation {

        @Test
        @DisplayName("DA-5: null is valid (platform default)")
        void nullValid() {
            assertThatCode(() -> DailyAttemptSafetyService.assertConfigurable(null))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "DA-6: {0} is valid")
        @ValueSource(ints = {1, 2, 3, 9, 10})
        @DisplayName("DA-6: 1..10 is valid")
        void validRangeAccepted(int value) {
            assertThatCode(() -> DailyAttemptSafetyService.assertConfigurable(value))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "DA-7: {0} is rejected")
        @ValueSource(ints = {0, -1, 11, 100})
        @DisplayName("DA-7: 0, negative and above-maximum are rejected as VALIDATION_ERROR")
        void invalidRangeRejected(int value) {
            assertThatThrownBy(() -> DailyAttemptSafetyService.assertConfigurable(value))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("maxDailyAttempts")
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo("VALIDATION_ERROR"));
        }
    }

    @Nested
    class Admission {

        @Test
        @DisplayName("DA-8: the bucket row is created before the conditional update")
        void bucketRowCreatedFirst() {
            grants(1);
            service.admit(TENANT, CONTACT, TZ, null);
            Mockito.verify(repository).insertBucketRow(TENANT, CONTACT, DAY);
        }

        @Test
        @DisplayName("DA-9: the day comes from the snapshot timezone, not the JVM")
        void dayComesFromSnapshotTimezone() {
            grants(1);
            service.admit(TENANT, CONTACT, TZ, null);
            // The same seam VB-6C uses, so both daily controls roll over at the
            // same instant.
            Mockito.verify(dialLimitService).resolveUsageDate(TZ);
            Mockito.verify(repository).reserve(TENANT, CONTACT, DAY,
                    DailyAttemptSafetyService.MAX_DAILY_ATTEMPTS_PER_CONTACT);
        }

        @Test
        @DisplayName("DA-10: a granted slot is ADMITTED")
        void grantedIsAdmitted() {
            grants(1);
            assertThat(service.admit(TENANT, CONTACT, TZ, 3))
                    .isEqualTo(DailyAttemptSafetyService.AdmissionResult.ADMITTED);
        }

        @Test
        @DisplayName("DA-11: a refused slot is LIMIT_REACHED and consumes nothing")
        void refusedIsLimitReached() {
            grants(0);
            assertThat(service.admit(TENANT, CONTACT, TZ, 3))
                    .isEqualTo(DailyAttemptSafetyService.AdmissionResult.LIMIT_REACHED);
        }

        @Test
        @DisplayName("DA-12: the campaign ceiling is what reaches the conditional update")
        void campaignCeilingReachesTheUpdate() {
            grants(1);
            service.admit(TENANT, CONTACT, TZ, 2);
            Mockito.verify(repository).reserve(TENANT, CONTACT, DAY, 2);
        }

        @Test
        @DisplayName("DA-13: an invalid snapshot timezone fails the dispatch, not the count")
        void invalidTimezoneFailsBeforeCounting() {
            Mockito.when(dialLimitService.resolveUsageDate(TZ))
                    .thenThrow(new ExecutionTimezoneInvalidException("bad zone"));
            assertThatThrownBy(() -> service.admit(TENANT, CONTACT, TZ, null))
                    .isInstanceOf(ExecutionTimezoneInvalidException.class);
            Mockito.verify(repository, Mockito.never())
                    .insertBucketRow(Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(repository, Mockito.never())
                    .reserve(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt());
        }

        @Test
        @DisplayName("DA-14: there is no release path — a dispatch is a dispatch")
        void noReleasePathExists() {
            // The increment happens in the admitting transaction, so there is
            // nothing to confirm later and nothing a crash can strand. A
            // release method would be dead code inviting a bug: a released
            // "attempt" was still a dial and the contact may have rung.
            boolean hasRelease = java.util.Arrays
                    .stream(VoiceBlastDailyAttemptRepository.class.getDeclaredMethods())
                    .anyMatch(m -> m.getName().toLowerCase().contains("release"));
            assertThat(hasRelease)
                    .as("a decrement/release would reintroduce stranding risk")
                    .isFalse();
        }
    }

    @Nested
    class SeparationFromVb6c {

        @Test
        @DisplayName("DA-15: the two ceilings are independent constants, not aliases")
        void ceilingsAreIndependent() {
            // If these were ever made equal it would suggest the two controls
            // had been conflated. They answer different questions.
            assertThat(DailyAttemptSafetyService.MAX_DAILY_ATTEMPTS_PER_CONTACT)
                    .isNotEqualTo(DailyDialLimitService.PLATFORM_DAILY_DIAL_LIMIT);
            assertThat(DailyDialLimitService.PLATFORM_DAILY_DIAL_LIMIT).isEqualTo(3);
            assertThat(DailyAttemptSafetyService.MAX_DAILY_ATTEMPTS_PER_CONTACT).isEqualTo(10);
        }

        @Test
        @DisplayName("DA-16: the new limit code is pre-dispatch, so it never spends retry budget")
        void newLimitCodeIsPreDispatch() {
            FailureClassification classification =
                    FailureClassification.of(CallFailureCode.DAILY_ATTEMPT_LIMIT_REACHED);
            assertThat(classification.isPreDispatch()).isTrue();
            assertThat(classification.category())
                    .as("no product category governs a pre-dispatch rejection")
                    .isNull();

            // A campaign that allows the maximum number of retries still cannot
            // retry past the daily ceiling, because the ceiling is enforced at
            // dispatch and is not retry budget.
            RetryPolicySpec permissive = new RetryPolicySpec(10, 60, RetryStrategy.FIXED, null);
            RetryDecision decision = new RetryPolicyService().evaluate(permissive,
                    CallFailureCode.DAILY_ATTEMPT_LIMIT_REACHED.getCode(), 1,
                    java.time.Instant.parse("2026-09-27T10:00:00Z"));
            assertThat(decision.retryable()).isFalse();
            assertThat(decision.reason()).contains("PRE_DISPATCH");
        }
    }
}
