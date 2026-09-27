package com.shivang.obd.voice.call;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.campaign.CallFailureCode;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VB-6D.1 — the canonical failure-taxonomy boundary.
 *
 * <p>This suite is the executable form of the phase's core invariant:
 * <em>no provider-supplied string can become a business failure code.</em> It
 * pins the closed cause→code table, the closed set of codes the mapper can
 * emit, and the fact that every emitted value is a real
 * {@link CallFailureCode} constant that the retry gate can therefore classify.
 *
 * <p>The cross-module assertion (mapper in {@code voice}, vocabulary owned by
 * {@code campaign}) is deliberate: this test lives in the {@code voice} test
 * tree but references the campaign enum, which is safe because {@code campaign}
 * already depends on {@code voice} — the reverse edge is never created.
 */
class HangupCauseMapperTest {

    @Nested
    class KnownCauseMappings {

        @ParameterizedTest(name = "cause {0} -> {1}")
        @CsvSource({
                "17,              BUSY",
                "USER_BUSY,       BUSY",
                "19,              NO_ANSWER",
                "NO_ANSWER,       NO_ANSWER",
                "21,              REJECTED",
                "CALL_REJECTED,   REJECTED",
                "34,              CONGESTION",
                "NO_CIRCUIT_AVAILABLE, CONGESTION",
                "41,              TEMPORARY_FAILURE",
                "NORMAL_TEMPORARY_FAILURE, TEMPORARY_FAILURE",
                "47,              RESOURCE_UNAVAILABLE",
                "RESOURCE_UNAVAILABLE,    RESOURCE_UNAVAILABLE"
        })
        @DisplayName("TAX-1: every supported cause maps to its existing canonical code")
        void supportedCausesMapToCanonicalCodes(String cause, String expected) {
            assertThat(HangupCauseMapper.toFailureCode(cause)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "cause {0} is classified as a canonical code")
        @ValueSource(strings = {
                "17", "USER_BUSY", "19", "NO_ANSWER", "21", "CALL_REJECTED",
                "34", "NO_CIRCUIT_AVAILABLE", "41", "NORMAL_TEMPORARY_FAILURE",
                "47", "RESOURCE_UNAVAILABLE"
        })
        @DisplayName("TAX-2: every mapped result is a real CallFailureCode constant")
        void everyMappedResultIsCanonical(String cause) {
            String code = HangupCauseMapper.toFailureCode(cause);
            assertThat(CallFailureCode.fromCode(code))
                    .as("cause %s must yield a canonical code, got %s", cause, code)
                    .isPresent();
        }

        @ParameterizedTest(name = "cause '{0}' -> {1} (surrounding whitespace ignored)")
        @CsvSource({
                "' 17 ',             BUSY",
                "' user_busy ',      BUSY",
                "' 19 ',             NO_ANSWER",
                "'  CALL_REJECTED ', REJECTED"
        })
        @DisplayName("TAX-2b: surrounding whitespace is trimmed before mapping")
        void surroundingWhitespaceIsTrimmed(String cause, String expected) {
            // Providers pad header values; a padded cause is still the same
            // cause, so it must not fall through to the unknown bucket.
            assertThat(HangupCauseMapper.toFailureCode(cause)).isEqualTo(expected);
        }
    }

    @Nested
    class UnknownCauseBehaviour {

        @ParameterizedTest(name = "unknown cause {0} -> HANGUP_UNKNOWN")
        @ValueSource(strings = {
                "NOT_A_CAUSE",
                "CARRIER_SPECIFIC_WEIRD_CODE",
                "27",        // Q.850 destination off-hook — no reliable mapping
                "15",        // Q.850 unallocated number
                "22",        // Q.850 network out of order
                "31",        // Q.850 normal temporary failure
                "0",
                "999999",
                "  ",                   // blank
                "16",                   // normal clearing is NOT a failure
                "NORMAL_CLEARING",      // symbolic form likewise
                "normal_clearing"       // case-insensitive, as before
        })
        @DisplayName("TAX-3: unmapped, blank and normal-clearing causes resolve to the canonical unknown code")
        void unmappedCausesResolveToCanonicalUnknown(String cause) {
            assertThat(HangupCauseMapper.toFailureCode(cause))
                    .isEqualTo(CallFailureCode.HANGUP_UNKNOWN.getCode());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("TAX-4: null/empty cause resolves to the canonical unknown code, never null")
        void nullCauseResolvesToCanonicalUnknown(String cause) {
            assertThat(HangupCauseMapper.toFailureCode(cause))
                    .isEqualTo("HANGUP_UNKNOWN")
                    .isNotNull();
        }

        @Test
        @DisplayName("TAX-5: an unknown cause NEVER leaks provider text into the result")
        void unknownCauseNeverLeaksProviderText() {
            for (String cause : new String[] {
                    "CARRIER_XYZ_FAILURE", "27", "15", "weird", "HANGUP_NOW",
                    "1", "x".repeat(200)}) {
                String code = HangupCauseMapper.toFailureCode(cause);
                // The pre-VB-6D.1 defect produced "HANGUP_" + cause. The
                // canonical unknown code legitimately starts with "HANGUP_", so
                // the invariant is not a prefix check: the provider text must
                // not appear in the result at all, and the result must be one
                // of the fixed canonical values.
                assertThat(code)
                        .as("cause %s must not leak provider text into %s", cause, code)
                        .doesNotContain(cause)
                        .isEqualTo(CallFailureCode.HANGUP_UNKNOWN.getCode())
                        .isIn(HangupCauseMapper.supportedFailureCodes());
            }
        }

        @Test
        @DisplayName("TAX-6: the mapper's output set is closed and fully canonical")
        void outputSetIsClosedAndCanonical() {
            Set<String> supported = HangupCauseMapper.supportedFailureCodes();

            assertThat(supported).isNotEmpty();
            // Every reachable value is a canonical constant — this is the
            // property that makes the persistence boundary safe.
            for (String code : supported) {
                assertThat(CallFailureCode.fromCode(code))
                        .as("emitted code %s must be canonical", code)
                        .isPresent();
            }
            // And the set is exactly what a broad sweep of causes produces.
            Set<String> observed = new java.util.LinkedHashSet<>();
            observed.add(HangupCauseMapper.toFailureCode("17"));
            observed.add(HangupCauseMapper.toFailureCode("19"));
            observed.add(HangupCauseMapper.toFailureCode("21"));
            observed.add(HangupCauseMapper.toFailureCode("34"));
            observed.add(HangupCauseMapper.toFailureCode("41"));
            observed.add(HangupCauseMapper.toFailureCode("47"));
            observed.add(HangupCauseMapper.toFailureCode("nonsense"));
            assertThat(observed).isEqualTo(supported);
        }
    }

    @Nested
    class NormalClearingIsNotAFailure {

        @ParameterizedTest(name = "cause {0} is a normal release")
        @ValueSource(strings = {"16", "NORMAL_CLEARING", "normal_clearing", "Normal_Clearing", " 16 "})
        @DisplayName("TAX-7: normal clearing is recognised as success, numerically and symbolically")
        void normalClearingRecognised(String cause) {
            assertThat(HangupCauseMapper.isNormalClearing(cause)).isTrue();
        }

        @ParameterizedTest(name = "cause {0} is not a normal release")
        @ValueSource(strings = {"17", "19", "21", "34", "41", "47", "27", "NOPE"})
        @DisplayName("TAX-8: failure causes and unknown causes are not normal clearing")
        void failureCausesNotNormalClearing(String cause) {
            assertThat(HangupCauseMapper.isNormalClearing(cause)).isFalse();
        }

        @Test
        @DisplayName("TAX-9: an absent cause is NOT optimistically treated as success")
        void absentCauseIsNotSuccess() {
            // VB-6D.1 correction: the agent-outbound path previously treated a
            // null cause as a successful completion, guessing. An unknown
            // outcome is now a canonical failure, not an assumed success.
            assertThat(HangupCauseMapper.isNormalClearing(null)).isFalse();
            assertThat(HangupCauseMapper.isNormalClearing("")).isFalse();
            assertThat(HangupCauseMapper.isNormalClearing("   ")).isFalse();
        }
    }
}
