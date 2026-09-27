package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VB-6D.2 — the retry policy model: delay representation, count semantics,
 * canonical-failure classification, and the evaluation contract.
 *
 * <p>Structured around the phase's safety properties rather than around the
 * classes: a permanent failure can never be made retryable, a pre-dispatch
 * rejection never spends retry budget, and the decision is a pure function.
 */
class RetryPolicyModelTest {

    private final RetryPolicyService policy = new RetryPolicyService();

    private static final Instant FAILED_AT = Instant.parse("2026-09-27T10:00:00Z");

    private static RetryPolicySpec flat(int maxRetries, Integer delaySeconds) {
        return new RetryPolicySpec(maxRetries, delaySeconds, RetryStrategy.FIXED);
    }

    private static RetryRule rule(RetryRuleCategory category, int maxRetries, String delay) {
        return new RetryRule(category, Boolean.TRUE, maxRetries, RetryDelay.parse(delay));
    }

    // === A. rule parsing / delay representation ===

    @Nested
    class DelayRepresentation {

        @ParameterizedTest(name = "\"{0}\" parses to {1}s")
        @CsvSource({
                "00:01, 1",
                "00:30, 30",
                "01:00, 60",
                "05:00, 300",
                "99:59, 5999"
        })
        @DisplayName("MDL-1: MM:SS parses to the expected duration")
        void parsesMmSs(String raw, long expectedSeconds) {
            assertThat(RetryDelay.parse(raw).value().getSeconds()).isEqualTo(expectedSeconds);
        }

        @ParameterizedTest(name = "\"{0}\" round-trips")
        @ValueSource(strings = {"00:01", "00:30", "01:00", "05:00", "99:59"})
        @DisplayName("MDL-2: toString round-trips through parse")
        void roundTrips(String raw) {
            assertThat(RetryDelay.parse(raw).toString()).isEqualTo(raw);
        }

        @ParameterizedTest(name = "\"{0}\" is rejected")
        @ValueSource(strings = {
                "5", "5:0", "005:00", "5:00:00", "1h30m", "00:60", "00:99",
                "100:00", "abc", "00-30", "00:30:45", "-1:00", "00:00"
        })
        @DisplayName("MDL-3: malformed, out-of-range and zero delays are rejected")
        void rejectsMalformedDelays(String raw) {
            assertThatThrownBy(() -> RetryDelay.parse(raw))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retryDelay");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("MDL-4: a null/blank delay is rejected with a format hint")
        void rejectsNullDelay(String raw) {
            assertThatThrownBy(() -> RetryDelay.parse(raw))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("MM:SS");
        }

        @Test
        @DisplayName("MDL-5: applying a delay is timezone-independent Instant arithmetic")
        void delayApplicationIsTimezoneIndependent() {
            // Same inputs, three different default zones: the result must not
            // move. This is the property that makes the delay testable at all.
            Instant base = FAILED_AT;
            Instant expected = base.plus(Duration.ofMinutes(5));
            for (String zone : List.of("UTC", "Asia/Kolkata", "America/Los_Angeles")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
                try {
                    assertThat(RetryDelay.parse("05:00").applyTo(base)).isEqualTo(expected);
                } finally {
                    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
                }
            }
        }
    }

    // === C. retry count semantics ===

    @Nested
    class CountSemantics {

        @Test
        @DisplayName("CNT-1: maxRetries counts RETRIES, so maxTotalAttempts = 1 + maxRetries")
        void maxRetriesIsNotMaxAttempts() {
            assertThat(RetryRule.of(RetryRuleCategory.NO_ANSWER, 2,
                    Duration.ofMinutes(5)).maxTotalAttempts())
                    .as("2 retries means 3 attempts in total")
                    .isEqualTo(3);
            assertThat(RetryRule.of(RetryRuleCategory.NO_ANSWER, 0,
                    Duration.ofMinutes(1)).maxTotalAttempts()).isEqualTo(1);
            assertThat(RetryRule.of(RetryRuleCategory.BUSY, 10,
                    Duration.ofMinutes(1)).maxTotalAttempts()).isEqualTo(11);
        }

        @Test
        @DisplayName("CNT-2: attempts 1..N are allowed and N+1 is refused")
        void attemptsUpToTheCeilingThenRefused() {
            RetryPolicySpec spec = flat(2, 300);
            for (int attempt = 1; attempt <= 2; attempt++) {
                assertThat(policy.evaluate(spec, "NO_ANSWER", attempt, FAILED_AT).retryable())
                        .as("attempt %s of 3 must be retried", attempt)
                        .isTrue();
            }
            RetryDecision exhausted = policy.evaluate(spec, "NO_ANSWER", 3, FAILED_AT);
            assertThat(exhausted.retryable()).isFalse();
            assertThat(exhausted.reason()).contains("RETRIES_EXHAUSTED");
        }

        @Test
        @DisplayName("CNT-3: a legacy flat policy keeps its pre-VB-6D.2 meaning")
        void legacyFlatPolicyUnchanged() {
            // Pre-VB-6D.2: maxAttempts=2 -> maxTotalAttempts 1+2=3, delay
            // intervalSeconds. Unchanged by this phase.
            RetryDecision decision = policy.evaluate(flat(2, 300), "BUSY", 1, FAILED_AT);
            assertThat(decision.retryable()).isTrue();
            assertThat(decision.maxTotalAttempts()).isEqualTo(3);
            assertThat(decision.nextEligibleAt()).isEqualTo(FAILED_AT.plusSeconds(300));
        }

        @Test
        @DisplayName("CNT-4: a campaign with no retries configured retries nothing")
        void zeroAllowanceRetriesNothing() {
            assertThat(policy.evaluate(flat(0, null), "NO_ANSWER", 1, FAILED_AT).retryable())
                    .isFalse();
            assertThat(policy.evaluate(null, "NO_ANSWER", 1, FAILED_AT).retryable())
                    .as("a null policy must not mean 'retry everything'")
                    .isFalse();
        }

        @Test
        @DisplayName("CNT-5: retries are not consumed by queue re-processing or capacity rejection")
        void preDispatchNeverConsumesBudget() {
            // Capacity rejection requeues the SAME attempt (attempt number
            // unchanged), so the budget is untouched by construction. These
            // codes additionally never reach a retry decision.
            for (CallFailureCode preDispatch : List.of(
                    CallFailureCode.TEMPORARILY_UNAVAILABLE,
                    CallFailureCode.PROVIDER_UNAVAILABLE,
                    CallFailureCode.GATEWAY_CAPACITY_EXHAUSTED)) {
                RetryDecision decision = policy.evaluate(flat(3, 300), preDispatch.getCode(),
                        1, FAILED_AT);
                assertThat(decision.retryable())
                        .as("%s is pre-dispatch and must not create a retry", preDispatch)
                        .isFalse();
                assertThat(decision.reason()).contains("PRE_DISPATCH");
            }
        }
    }

    // === D. retry delay calculation ===

    @Nested
    class DelayCalculation {

        @Test
        @DisplayName("DLY-1: the delay is measured from the failure instant")
        void delayMeasuredFromFailure() {
            RetryPolicySpec spec = new RetryPolicySpec(1, null, RetryStrategy.FIXED,
                    List.of(rule(RetryRuleCategory.NO_ANSWER, 1, "10:00")));
            RetryDecision decision = policy.evaluate(spec, "NO_ANSWER", 1, FAILED_AT);
            assertThat(decision.nextEligibleAt()).isEqualTo(FAILED_AT.plusSeconds(600));
        }

        @Test
        @DisplayName("DLY-2: a per-category delay overrides the flat interval")
        void perCategoryDelayWins() {
            RetryPolicySpec spec = new RetryPolicySpec(5, 60, RetryStrategy.FIXED,
                    List.of(rule(RetryRuleCategory.BUSY, 5, "30:00")));
            assertThat(policy.evaluate(spec, "BUSY", 1, FAILED_AT).nextEligibleAt())
                    .isEqualTo(FAILED_AT.plusSeconds(1800));
        }

        @Test
        @DisplayName("DLY-3: a category without a rule falls back to the flat allowance and interval")
        void fallsBackToFlatAllowance() {
            RetryPolicySpec spec = new RetryPolicySpec(1, 120, RetryStrategy.FIXED,
                    List.of(rule(RetryRuleCategory.BUSY, 5, "30:00")));
            // NO_ANSWER has no rule -> flat allowance (1 retry) and flat delay.
            RetryDecision decision = policy.evaluate(spec, "NO_ANSWER", 1, FAILED_AT);
            assertThat(decision.retryable()).isTrue();
            assertThat(decision.maxTotalAttempts()).isEqualTo(2);
            assertThat(decision.nextEligibleAt()).isEqualTo(FAILED_AT.plusSeconds(120));
        }

        @Test
        @DisplayName("DLY-4: an explicit rule can switch a category OFF that the flat policy allows")
        void ruleCanRestrictTheFlatAllowance() {
            RetryPolicySpec spec = new RetryPolicySpec(5, 60, RetryStrategy.FIXED,
                    List.of(RetryRule.disabled(RetryRuleCategory.BUSY)));
            assertThat(policy.evaluate(spec, "BUSY", 1, FAILED_AT).retryable())
                    .as("campaign policy may restrict, never enable")
                    .isFalse();
            // Other categories keep the flat allowance.
            assertThat(policy.evaluate(spec, "NO_ANSWER", 1, FAILED_AT).retryable()).isTrue();
        }
    }

    // === E/F. canonical failure -> category, and the permanent floor ===

    @Nested
    class CanonicalMappingAndPermanentFloor {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "NO_ANSWER,      NO_ANSWER",
                "AGENT_NO_ANSWER, NO_ANSWER",
                "BUSY,           BUSY",
                "AGENT_BUSY,     BUSY",
                "HANGUP_UNKNOWN, HANGUP",
                "CALLER_HANGUP,  HANGUP",
                "REJECTED,       HANGUP",
                "TEMPORARY_FAILURE, FAILED",
                "CONGESTION,     FAILED",
                "PLAYBACK_FAILED, FAILED",
                "DTMF_PLAYBACK_FAILED, FAILED"
        })
        @DisplayName("MAP-1: canonical failures map to their product category")
        void canonicalFailuresMapToCategories(CallFailureCode code, RetryRuleCategory expected) {
            assertThat(FailureClassification.of(code).category()).isEqualTo(expected);
        }

        @Test
        @DisplayName("MAP-2: an arbitrary provider string cannot choose a category")
        void arbitraryStringCannotChooseCategory() {
            // Nothing resolves outside the vocabulary: an unknown value becomes
            // HANGUP_UNKNOWN and therefore the HANGUP rule, every time.
            for (String junk : List.of("HANGUP_27", "SOME_CARRIER_CODE", "busy", "COMPLETED",
                    "SWITCHED_OFF", "NOT_REACHABLE", "")) {
                FailureClassification classification = FailureClassification.of(junk);
                assertThat(classification.category())
                        .as("value %s must resolve to the HANGUP category", junk)
                        .isEqualTo(RetryRuleCategory.HANGUP);
                // And the value that produced it is a canonical code, so the
                // retry gate classifies it by policy rather than by an empty
                // lookup.
                assertThat(CallFailureCode.canonicalize(junk))
                        .isEqualTo(CallFailureCode.HANGUP_UNKNOWN);
            }
        }

        @ParameterizedTest
        @EnumSource(value = CallFailureCode.class,
                names = {"REJECTED", "DIAL_FAILED", "PLAYBACK_CONFIG_INVALID",
                        "DTMF_CONFIG_INVALID", "CAMPAIGN_NOT_FOUND", "CONTACT_INVALID",
                        "EXECUTION_CONFIG_MISSING", "EXECUTION_TIMEZONE_INVALID",
                        "DAILY_LIMIT_REACHED", "AGENT_TENANT_MISMATCH"})
        @DisplayName("PERM-1: a PERMANENT failure can never be made retryable by configuration")
        void permanentFailuresAreNeverRetryable(CallFailureCode permanent) {
            // The most permissive policy imaginable: 10 retries for every
            // category. Permanence must still win.
            RetryPolicySpec everything = new RetryPolicySpec(10, 60, RetryStrategy.FIXED,
                    java.util.Arrays.stream(RetryRuleCategory.values())
                            .map(c -> rule(c, 10, "01:00"))
                            .toList());
            RetryDecision decision = policy.evaluate(everything, permanent.getCode(), 1, FAILED_AT);
            assertThat(decision.retryable())
                    .as("%s is permanent and must never retry", permanent)
                    .isFalse();
        }

        @Test
        @DisplayName("PERM-2: effectiveRetry is an AND, never an OR")
        void effectiveRetryIsConjunctive() {
            // Policy allows, failure not eligible -> no retry.
            RetryPolicySpec permissive = flat(5, 60);
            assertThat(policy.evaluate(permissive, "DNC_BLOCKED", 1, FAILED_AT).retryable())
                    .as("compliance rejection: policy cannot override")
                    .isFalse();
            assertThat(policy.evaluate(permissive, "DIAL_FAILED", 1, FAILED_AT).retryable())
                    .as("permanent: policy cannot override")
                    .isFalse();
        }
    }

    // === G. HANGUP_UNKNOWN ===

    @Nested
    class HangupUnknownBehaviour {

        @Test
        @DisplayName("HANG-1: HANGUP_UNKNOWN follows the HANGUP rule explicitly")
        void hangupUnknownFollowsHangupRule() {
            RetryPolicySpec spec = new RetryPolicySpec(0, null, RetryStrategy.FIXED,
                    List.of(rule(RetryRuleCategory.HANGUP, 2, "15:00")));

            RetryDecision allowed = policy.evaluate(spec, "HANGUP_UNKNOWN", 1, FAILED_AT);
            assertThat(allowed.retryable())
                    .as("HANGUP_UNKNOWN is governed by the HANGUP rule, not by an accident")
                    .isTrue();
            assertThat(allowed.classification().category()).isEqualTo(RetryRuleCategory.HANGUP);
            assertThat(allowed.nextEligibleAt()).isEqualTo(FAILED_AT.plusSeconds(900));
        }

        @Test
        @DisplayName("HANG-2: a disabled HANGUP rule suppresses HANGUP_UNKNOWN")
        void disabledHangupRuleSuppressesUnknown() {
            RetryPolicySpec spec = new RetryPolicySpec(0, null, RetryStrategy.FIXED,
                    List.of(RetryRule.disabled(RetryRuleCategory.HANGUP)));
            assertThat(policy.evaluate(spec, "HANGUP_UNKNOWN", 1, FAILED_AT).retryable()).isFalse();
        }

        @Test
        @DisplayName("HANG-3: VB-6D.1's HANGUP_UNKNOWN=TEMPORARY classification is unchanged")
        void temporaryClassificationUnchanged() {
            // The taxonomy constant itself is untouched by this phase; the
            // policy makes its treatment explicit instead of leaving it to the
            // empty-lookup fallback.
            assertThat(CallFailureCode.HANGUP_UNKNOWN.getRetryClass())
                    .isEqualTo(CallFailureCode.RetryClass.TEMPORARY);
        }
    }

    // === H. SWITCHED_OFF / NOT_REACHABLE ===

    @Nested
    class UnsupportedProviderCategories {

        @Test
        @DisplayName("UNSUP-1: no canonical failure currently maps to SWITCHED_OFF/NOT_REACHABLE")
        void noCanonicalCodeFeedsTheReservedCategories() {
            for (CallFailureCode code : CallFailureCode.values()) {
                RetryRuleCategory category = FailureClassification.categoryOf(code);
                assertThat(category)
                        .as("%s must not claim a category the provider cannot establish", code)
                        .isNotIn(RetryRuleCategory.SWITCHED_OFF, RetryRuleCategory.NOT_REACHABLE);
            }
        }

        @Test
        @DisplayName("UNSUP-2: carrier causes that might mean those outcomes land on HANGUP")
        void carrierCausesLandOnHangup() {
            for (String cause : List.of("27", "15", "22", "31", "SUBSCRIBER_OFF_HOOK")) {
                assertThat(FailureClassification.of(cause).category())
                        .as("unclassifiable provider outcome %s", cause)
                        .isEqualTo(RetryRuleCategory.HANGUP);
            }
        }

        @Test
        @DisplayName("UNSUP-3: the reserved categories remain configurable for the future")
        void reservedCategoriesRemainConfigurable() {
            // They are accepted policy categories, so a future reliable provider
            // mapping needs no schema or model change.
            RetryPolicySpec spec = new RetryPolicySpec(0, null, RetryStrategy.FIXED,
                    List.of(rule(RetryRuleCategory.SWITCHED_OFF, 3, "10:00")));
            assertThat(spec.ruleFor(RetryRuleCategory.SWITCHED_OFF).effectiveMaxRetries())
                    .isEqualTo(3);
        }
    }

    // === L. determinism / purity ===

    @Nested
    class DeterminismAndPurity {

        @Test
        @DisplayName("DET-1: the same inputs always produce the same decision")
        void deterministic() {
            RetryPolicySpec spec = new RetryPolicySpec(2, 300, RetryStrategy.FIXED,
                    List.of(rule(RetryRuleCategory.NO_ANSWER, 1, "05:00")));
            RetryDecision first = policy.evaluate(spec, "NO_ANSWER", 1, FAILED_AT);
            for (int i = 0; i < 50; i++) {
                assertThat(policy.evaluate(spec, "NO_ANSWER", 1, FAILED_AT))
                        .isEqualTo(first);
            }
        }

        @Test
        @DisplayName("DET-2: the evaluator holds no state and needs no collaborators")
        void pureNoCollaborators() {
            // Constructed with no arguments: the class cannot increment a
            // counter, reserve capacity, create an attempt, or call a provider,
            // because it has no repository or client to do so with.
            assertThat(RetryPolicyService.class.getDeclaredConstructors())
                    .singleElement()
                    .satisfies(c -> assertThat(c.getParameterCount()).isZero());
        }

        @Test
        @DisplayName("DET-3: every decision carries an explanatory reason")
        void everyDecisionIsExplainable() {
            RetryPolicySpec spec = flat(1, 60);
            for (String code : List.of("NO_ANSWER", "BUSY", "HANGUP_UNKNOWN", "DNC_BLOCKED",
                    "DIAL_FAILED", "TEMPORARY_FAILURE", "HANGUP_27")) {
                assertThat(policy.evaluate(spec, code, 1, FAILED_AT).reason())
                        .as("code %s", code)
                        .isNotBlank();
            }
        }
    }
}
