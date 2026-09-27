package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.common.exception.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VB-6E — maximum call duration policy.
 *
 * <p>Pins the three things that make the feature trustworthy: the default, the
 * range, and the fact that the <em>effective</em> value is always deterministic.
 */
class MaxCallDurationPolicyTest {

    @Nested
    class Default {

        @Test
        @DisplayName("DUR-1: no configuration means the 300s platform default")
        void nullMeansDefault() {
            assertThat(MaxCallDurationPolicy.effectiveSeconds(null))
                    .isEqualTo(300)
                    .isEqualTo(MaxCallDurationPolicy.DEFAULT_MAX_CALL_DURATION_SECONDS);
        }

        @Test
        @DisplayName("DUR-2: the default is 300 seconds (5 minutes)")
        void defaultIsFiveMinutes() {
            assertThat(MaxCallDurationPolicy.DEFAULT_MAX_CALL_DURATION_SECONDS).isEqualTo(300);
        }
    }

    @Nested
    class EffectiveValue {

        @ParameterizedTest(name = "DUR-3: configured {0} is used verbatim")
        @ValueSource(ints = {1, 2, 30, 60, 180, 300, 900, 3600})
        @DisplayName("DUR-3: a configured value in range is used verbatim")
        void configuredValueIsUsed(int seconds) {
            assertThat(MaxCallDurationPolicy.effectiveSeconds(seconds)).isEqualTo(seconds);
        }

        @Test
        @DisplayName("DUR-4: an out-of-range value is clamped, never propagated")
        void outOfRangeIsClamped() {
            // Validation rejects these before they can be stored, so this is the
            // last line of defence rather than the primary control.
            assertThat(MaxCallDurationPolicy.effectiveSeconds(0)).isEqualTo(1);
            assertThat(MaxCallDurationPolicy.effectiveSeconds(-90)).isEqualTo(1);
            assertThat(MaxCallDurationPolicy.effectiveSeconds(3601)).isEqualTo(3600);
            assertThat(MaxCallDurationPolicy.effectiveSeconds(999_999)).isEqualTo(3600);
        }

        @Test
        @DisplayName("DUR-5: the effective value is always inside the declared range")
        void effectiveValueIsAlwaysInRange() {
            for (int candidate : new int[] {-1, 0, 1, 300, 3600, 3601, Integer.MAX_VALUE}) {
                assertThat(MaxCallDurationPolicy.effectiveSeconds(candidate))
                        .isBetween(MaxCallDurationPolicy.MIN_MAX_CALL_DURATION_SECONDS,
                                MaxCallDurationPolicy.MAX_MAX_CALL_DURATION_SECONDS);
            }
        }
    }

    @Nested
    class Validation {

        @Test
        @DisplayName("DUR-6: null is valid (platform default)")
        void nullIsValid() {
            org.assertj.core.api.Assertions.assertThatCode(
                            () -> MaxCallDurationPolicy.assertConfigurable(null))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "DUR-7: {0} is valid")
        @ValueSource(ints = {1, 2, 300, 3599, 3600})
        @DisplayName("DUR-7: the whole 1..3600 range is valid")
        void validRangeAccepted(int seconds) {
            org.assertj.core.api.Assertions.assertThatCode(
                            () -> MaxCallDurationPolicy.assertConfigurable(seconds))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "DUR-8: {0} is rejected")
        @ValueSource(ints = {0, -1, -3600, 3601, 7200, Integer.MAX_VALUE})
        @DisplayName("DUR-8: outside 1..3600 is a VALIDATION_ERROR naming the field")
        void invalidRangeRejected(int seconds) {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(
                    () -> MaxCallDurationPolicy.assertConfigurable(seconds)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("maxCallDurationSeconds")
                    .hasMessageContaining("300");
        }

        @Test
        @DisplayName("DUR-9: the declared bounds are the documented ones")
        void boundsAreDocumented() {
            assertThat(MaxCallDurationPolicy.MIN_MAX_CALL_DURATION_SECONDS).isEqualTo(1);
            assertThat(MaxCallDurationPolicy.MAX_MAX_CALL_DURATION_SECONDS).isEqualTo(3600);
        }
    }

    @Nested
    class Semantics {

        @Test
        @DisplayName("DUR-10: the failure code is a dispatched, retryable outcome")
        void timeoutIsDispatchedNotPreDispatch() {
            // The subscriber was genuinely connected and heard part of the
            // blast, so this is NOT a pre-dispatch rejection and the campaign
            // retry policy legitimately governs it. Asserting this is what stops
            // a future edit from "helpfully" adding it to the pre-dispatch set.
            assertThat(FailureClassification.of(CallFailureCode.MAX_DURATION_EXCEEDED)
                    .isPreDispatch())
                    .as("a timed-out call WAS placed, so it is a dispatched outcome")
                    .isFalse();
            assertThat(CallFailureCode.MAX_DURATION_EXCEEDED.getRetryClass())
                    .isEqualTo(CallFailureCode.RetryClass.TEMPORARY);
        }

        @Test
        @DisplayName("DUR-11: a reconciled stale attempt is likewise dispatched, not pre-dispatch")
        void staleAttemptIsDispatched() {
            assertThat(FailureClassification.of(CallFailureCode.STALE_ATTEMPT_RECONCILED)
                    .isPreDispatch())
                    .as("the attempt held a VB-6C hold and a VB-6D.3 consumption")
                    .isFalse();
            assertThat(CallFailureCode.STALE_ATTEMPT_RECONCILED.getRetryClass())
                    .isEqualTo(CallFailureCode.RetryClass.TEMPORARY);
        }

        @Test
        @DisplayName("DUR-12: the new codes are distinct from the existing pre-dispatch codes")
        void newCodesDoNotCollide() {
            // MAX_DURATION_EXCEEDED must not be confused with any pre-dispatch
            // admission rejection, or a timeout would stop consuming retry budget.
            for (CallFailureCode code : new CallFailureCode[] {
                    CallFailureCode.MAX_DURATION_EXCEEDED,
                    CallFailureCode.STALE_ATTEMPT_RECONCILED}) {
                assertThat(CallFailureCode.fromCode(code.getCode()))
                        .as("%s must be a canonical code", code)
                        .contains(code);
                assertThat(FailureClassification.of(code).isPreDispatch()).isFalse();
            }
        }

        @Test
        @DisplayName("DUR-13: a deadline is an absolute instant derived from answer time")
        void deadlineDerivationIsDeterministic() {
            Instant answeredAt = Instant.parse("2026-09-27T10:00:00Z");

            Instant deadlineDefault = answeredAt.plusSeconds(
                    MaxCallDurationPolicy.effectiveSeconds(null));
            Instant deadlineConfigured = answeredAt.plusSeconds(
                    MaxCallDurationPolicy.effectiveSeconds(45));

            assertThat(deadlineDefault).isEqualTo(Instant.parse("2026-09-27T10:05:00Z"));
            assertThat(deadlineConfigured).isEqualTo(Instant.parse("2026-09-27T10:00:45Z"));
            assertThat(deadlineConfigured).isAfter(answeredAt);
        }
    }
}
