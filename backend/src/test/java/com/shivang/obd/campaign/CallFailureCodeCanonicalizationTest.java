package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.voice.call.HangupCauseMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VB-6D.1 — retry-classification safety for the canonical taxonomy.
 *
 * <p>Covers the half of the boundary that lives in {@code campaign}: given
 * whatever ends up in {@code failure_code}, the retry gate must reach a
 * <em>deterministic, canonical</em> decision rather than one that depends on a
 * lookup quietly returning empty.
 *
 * <p>Regression note: this phase deliberately did <strong>not</strong> flip the
 * classification of unknown outcomes. {@link CallFailureCode#canonicalize} makes
 * the unresolved case explicit and routes it through the reviewed
 * {@link CallFailureCode#HANGUP_UNKNOWN} constant instead of an implicit
 * {@code orElse(false)}; that constant is currently
 * {@link CallFailureCode.RetryClass#TEMPORARY}, so every canonical code keeps
 * exactly the classification it had. Whether unknown outcomes <em>should</em> be
 * retryable is a product decision (VB-6D audit OD-2/OD-6) and belongs to the
 * VB-6D.2 retry policy, where it becomes a one-constant change.
 */
class CallFailureCodeCanonicalizationTest {

    @Nested
    class Canonicalize {

        @Test
        @DisplayName("CANON-1: canonicalize returns the exact constant for a known code")
        void knownCodesResolveToThemselves() {
            for (CallFailureCode code : CallFailureCode.values()) {
                assertThat(CallFailureCode.canonicalize(code.getCode()))
                        .as("code %s must canonicalize to itself", code)
                        .isEqualTo(code);
            }
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "HANGUP_CALL_REJECTED_EXTRA", "SOME_FUTURE_CODE",
                "HANGUP_27", "HANGUP_MARS_OUT_OF_ORDER", "completed", "busy"})
        @DisplayName("CANON-2: absent or non-canonical values canonicalize to HANGUP_UNKNOWN, never null")
        void nonCanonicalValuesResolveToUnknown(String code) {
            assertThat(CallFailureCode.canonicalize(code))
                    .as("value %s must canonicalize to HANGUP_UNKNOWN", code)
                    .isEqualTo(CallFailureCode.HANGUP_UNKNOWN);
        }

        @Test
        @DisplayName("CANON-3: fromCode stays strict (empty for non-canonical) while canonicalize never is")
        void fromCodeRemainsStrict() {
            // Two different questions, deliberately distinct:
            //   fromCode   -> "is this a known code?"  (may be empty)
            //   canonicalize -> "which code does this mean?" (never empty)
            assertThat(CallFailureCode.fromCode("HANGUP_27")).isEmpty();
            assertThat(CallFailureCode.fromCode("SOME_FUTURE_CODE")).isEmpty();
            assertThat(CallFailureCode.canonicalize("HANGUP_27"))
                    .isEqualTo(CallFailureCode.HANGUP_UNKNOWN);
        }
    }

    @Nested
    class RetryClassificationSafety {

        @Test
        @DisplayName("RETRY-1: classification is derived from the canonical constant, not from lookup emptiness")
        void classificationNeverDependsOnAnEmptyLookup() {
            // A non-canonical value and its canonicalization must classify
            // identically — proof that the gate is not reading "empty" and
            // silently defaulting.
            for (String raw : List.of("HANGUP_27", "SOME_FUTURE_CODE", "", "   ")) {
                assertThat(CallFailureCode.retryClassOf(raw))
                        .as("value %s must classify as its canonicalization's class", raw)
                        .isEqualTo(CallFailureCode.canonicalize(raw).getRetryClass())
                        .isEqualTo(CallFailureCode.HANGUP_UNKNOWN.getRetryClass());
            }
        }

        @Test
        @DisplayName("RETRY-2: isPermanent agrees with retryClassOf for every code and for junk")
        void isPermanentDelegatesToRetryClassOf() {
            for (CallFailureCode code : CallFailureCode.values()) {
                assertThat(CallFailureCode.isPermanent(code.getCode()))
                        .isEqualTo(code.getRetryClass() == CallFailureCode.RetryClass.PERMANENT);
            }
            for (String junk : List.of("HANGUP_27", "SOME_FUTURE_CODE", "", "   ")) {
                assertThat(CallFailureCode.isPermanent(junk))
                        .isEqualTo(CallFailureCode.retryClassOf(junk)
                                == CallFailureCode.RetryClass.PERMANENT);
            }
        }

        @Test
        @DisplayName("RETRY-3: every canonical code the telephony mapper can emit is classifiable")
        void everyMappedOutcomeIsClassifiable() {
            for (String cause : List.of("17", "19", "21", "34", "41", "47", "27", "junk", "")) {
                String code = HangupCauseMapper.toFailureCode(cause);
                // Not a raw lookup: the consumer's real entry point. The point
                // is that a produced outcome is always a *known* code, so the
                // gate never has to reason about an unclassifiable value.
                assertThat(CallFailureCode.canonicalize(code))
                        .as("outcome %s for cause %s", code, cause)
                        .isNotNull()
                        .satisfies(c -> assertThat(c.getCode()).isEqualTo(code));
            }
        }
    }

    @Nested
    class NoInventedProviderCategories {

        @Test
        @DisplayName("SCOPE-1: SWITCHED_OFF and NOT_REACHABLE are NOT invented codes")
        void carrierSpecificCategoriesNotFabricated() {
            // The telephony boundary cannot reliably distinguish these: FreeSWITCH
            // surfaces them as carrier-dependent SIP/Q.850 causes. Fabricating a
            // mapping would present a guess as a classification, so they are
            // absent by design and those causes land on HANGUP_UNKNOWN.
            assertThat(CallFailureCode.fromCode("SWITCHED_OFF")).isEmpty();
            assertThat(CallFailureCode.fromCode("NOT_REACHABLE")).isEmpty();
            assertThat(List.of(CallFailureCode.values()).stream().map(Enum::name))
                    .doesNotContain("SWITCHED_OFF", "NOT_REACHABLE");

            // The causes that a carrier *might* use for them still resolve
            // deterministically rather than leaking through.
            for (String cause : List.of("27", "15", "22", "31", "SUBSCRIBER_OFF_HOOK")) {
                assertThat(HangupCauseMapper.toFailureCode(cause))
                        .isEqualTo(CallFailureCode.HANGUP_UNKNOWN.getCode());
            }
        }
    }
}
