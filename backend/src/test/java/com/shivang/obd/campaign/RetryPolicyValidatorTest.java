package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VB-6D.2 — the canonical domain validation rule for retry policy.
 *
 * <p>Covers the failures that bean validation on the DTO <em>structurally
 * cannot</em> express: a duplicate category, an enabled rule with retries but
 * no delay, and a delay on a rule that permits no retry. Those are only
 * detectable once the rules are seen together, which is why the domain layer
 * exists alongside the DTO constraints rather than instead of them.
 */
class RetryPolicyValidatorTest {

    private static RetryRule rule(RetryRuleCategory category, Boolean enabled,
                                  Integer maxRetries, String delay) {
        return new RetryRule(category, enabled, maxRetries,
                delay == null ? null : RetryDelay.parse(delay));
    }

    @Nested
    class ValidPolicies {

        @Test
        @DisplayName("VAL-1: a null policy is accepted")
        void nullAccepted() {
            assertThatCode(() -> RetryPolicyValidator.validate(null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("VAL-2: a legacy flat policy with no rules is accepted")
        void legacyFlatAccepted() {
            assertThatCode(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(2, 300, RetryStrategy.FIXED)))
                    .doesNotThrowAnyException();
            assertThatCode(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(0, null, RetryStrategy.FIXED)))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "VAL-3: a valid rule for {0} is accepted")
        @ValueSource(strings = {"NO_ANSWER", "BUSY", "HANGUP", "FAILED",
                "SWITCHED_OFF", "NOT_REACHABLE"})
        @DisplayName("VAL-3: one valid rule per supported category is accepted")
        void validRuleAccepted(RetryRuleCategory category) {
            assertThatCode(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(0, null, RetryStrategy.FIXED,
                            List.of(rule(category, Boolean.TRUE, 2, "05:00")))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("VAL-4: a full set of six distinct rules is accepted")
        void allSixRulesAccepted() {
            List<RetryRule> rules = java.util.Arrays.stream(RetryRuleCategory.values())
                    .map(c -> rule(c, Boolean.TRUE, 1, "01:00"))
                    .toList();
            assertThat(rules).hasSize(6);
            assertThatCode(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(0, null, RetryStrategy.FIXED, rules)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("VAL-5: an explicitly disabled rule with no retries is accepted")
        void disabledRuleAccepted() {
            assertThatCode(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(0, null, RetryStrategy.FIXED,
                            List.of(new RetryRule(RetryRuleCategory.BUSY,
                                    Boolean.FALSE, 0, null)))))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class InvalidPolicies {

        @Test
        @DisplayName("VAL-6: a duplicate category is rejected")
        void duplicateCategoryRejected() {
            BusinessException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    BusinessException.class,
                    () -> RetryPolicyValidator.validate(new RetryPolicySpec(
                            0, null, RetryStrategy.FIXED,
                            List.of(rule(RetryRuleCategory.BUSY, Boolean.TRUE, 1, "01:00"),
                                    rule(RetryRuleCategory.BUSY, Boolean.TRUE, 2, "02:00")))));
            assertThat(failure).hasMessageContaining("duplicate retry rule")
                    .hasMessageContaining("BUSY");
        }

        @Test
        @DisplayName("VAL-7: an enabled rule with retries but no delay is rejected")
        void enabledRuleWithoutDelayRejected() {
            assertThatThrownBy(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(0, null, RetryStrategy.FIXED,
                            List.of(rule(RetryRuleCategory.NO_ANSWER, Boolean.TRUE, 2, null)))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("requires a retryDelay");
        }

        @Test
        @DisplayName("VAL-8: a disabled rule carrying a delay is rejected")
        void disabledRuleWithDelayRejected() {
            assertThatThrownBy(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(0, null, RetryStrategy.FIXED,
                            List.of(rule(RetryRuleCategory.BUSY, Boolean.FALSE, 0, "05:00")))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("must not carry a retryDelay");
        }

        @Test
        @DisplayName("VAL-9: maxRetries=0 with a delay is rejected (a value that can never apply)")
        void zeroRetriesWithDelayRejected() {
            assertThatThrownBy(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(0, null, RetryStrategy.FIXED,
                            List.of(rule(RetryRuleCategory.BUSY, Boolean.TRUE, 0, "05:00")))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("maxRetries=0");
        }

        @ParameterizedTest(name = "VAL-10: maxRetries={0} is rejected")
        @ValueSource(ints = {-1, 11, 100})
        @DisplayName("VAL-10: a negative or above-maximum retry count is rejected")
        void outOfRangeRetriesRejected(int maxRetries) {
            assertThatThrownBy(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(0, null, RetryStrategy.FIXED,
                            List.of(rule(RetryRuleCategory.BUSY, Boolean.TRUE,
                                    maxRetries, "01:00")))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("maxRetries");
        }

        @Test
        @DisplayName("VAL-11: a null category is rejected")
        void nullCategoryRejected() {
            assertThatThrownBy(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(0, null, RetryStrategy.FIXED,
                            List.of(new RetryRule(null, Boolean.TRUE, 1,
                                    RetryDelay.parse("01:00"))))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("requires a category");
        }

        @Test
        @DisplayName("VAL-12: a null rule entry is rejected")
        void nullRuleRejected() {
            List<RetryRule> rules = new java.util.ArrayList<>();
            rules.add(null);
            assertThatThrownBy(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(0, null, RetryStrategy.FIXED, rules)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("null entry");
        }

        @Test
        @DisplayName("VAL-13: a flat allowance with retries but no interval is rejected")
        void flatAllowanceWithoutIntervalRejected() {
            assertThatThrownBy(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(3, null, RetryStrategy.FIXED)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("requires a positive intervalSeconds");
        }

        @Test
        @DisplayName("VAL-14: a negative flat allowance is rejected")
        void negativeFlatAllowanceRejected() {
            assertThatThrownBy(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(-1, null, RetryStrategy.FIXED)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("maxAttempts must be zero or greater");
        }

        @Test
        @DisplayName("VAL-15: the failure is a VALIDATION_ERROR (maps to the existing 400 contract)")
        void failureIsValidationError() {
            assertThatThrownBy(() -> RetryPolicyValidator.validate(
                    new RetryPolicySpec(0, null, RetryStrategy.FIXED,
                            List.of(rule(RetryRuleCategory.BUSY, Boolean.TRUE, 1, null)))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo("VALIDATION_ERROR"));
        }
    }

    @Nested
    class ViewLevelParsing {

        @ParameterizedTest(name = "VAL-17: out-of-range delay \"{0}\" is a VALIDATION_ERROR")
        @ValueSource(strings = {"00:60", "00:00", "99:60", "100:00", "00:99"})
        @DisplayName("VAL-17: a syntactically valid but out-of-range delay is a 400, not a 500")
        void outOfRangeDelayIsValidationError(String delay) {
            // The regression this guards: @Pattern matches these perfectly, so
            // they reach the parser, and a raw IllegalArgumentException there
            // would surface as HTTP 500 rather than the documented 400.
            com.shivang.obd.campaign.dto.RetryPolicyConfig view =
                    new com.shivang.obd.campaign.dto.RetryPolicyConfig(0, null,
                            RetryStrategy.FIXED,
                            List.of(new com.shivang.obd.campaign.dto.RetryRuleConfig(
                                    RetryRuleCategory.NO_ANSWER, Boolean.TRUE, 1, delay)));

            assertThatThrownBy(() -> RetryPolicyValidator.validateView(view))
                    .as("delay %s must map onto the 400 validation contract", delay)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("retryDelay")
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("VAL-18: a well-formed delay and a null policy pass validateView")
        void validViewPasses() {
            assertThatCode(() -> RetryPolicyValidator.validateView(null)).doesNotThrowAnyException();
            assertThatCode(() -> RetryPolicyValidator.validateView(
                    new com.shivang.obd.campaign.dto.RetryPolicyConfig(0, null,
                            RetryStrategy.FIXED, null))).doesNotThrowAnyException();
            assertThatCode(() -> RetryPolicyValidator.validateView(
                    new com.shivang.obd.campaign.dto.RetryPolicyConfig(0, null,
                            RetryStrategy.FIXED,
                            List.of(new com.shivang.obd.campaign.dto.RetryRuleConfig(
                                    RetryRuleCategory.BUSY, Boolean.TRUE, 2, "05:00")))))
                    .doesNotThrowAnyException();
            // A disabled rule legitimately has no delay.
            assertThatCode(() -> RetryPolicyValidator.validateView(
                    new com.shivang.obd.campaign.dto.RetryPolicyConfig(0, null,
                            RetryStrategy.FIXED,
                            List.of(new com.shivang.obd.campaign.dto.RetryRuleConfig(
                                    RetryRuleCategory.BUSY, Boolean.FALSE, 0, null)))))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class RuleResolution {

        @ParameterizedTest(name = "VAL-16: flat {0} retries -> {1} total attempts")
        @CsvSource({"0, 1", "1, 2", "2, 3", "10, 11"})
        @DisplayName("VAL-16: the flat allowance resolves to 1 + maxAttempts for every category")
        void flatResolvesForEveryCategory(int retries, int expectedTotal) {
            RetryPolicySpec spec = new RetryPolicySpec(retries,
                    retries > 0 ? 300 : null, RetryStrategy.FIXED);
            for (RetryRuleCategory category : RetryRuleCategory.values()) {
                assertThat(spec.ruleFor(category).maxTotalAttempts())
                        .as("category %s with flat %s retries", category, retries)
                        .isEqualTo(expectedTotal);
            }
        }

        @Test
        @DisplayName("VAL-17: an explicit rule takes precedence over the flat allowance")
        void explicitRuleWins() {
            RetryPolicySpec spec = new RetryPolicySpec(5, 300, RetryStrategy.FIXED,
                    List.of(rule(RetryRuleCategory.NO_ANSWER, Boolean.TRUE, 1, "01:00")));
            assertThat(spec.ruleFor(RetryRuleCategory.NO_ANSWER).maxTotalAttempts()).isEqualTo(2);
            assertThat(spec.ruleFor(RetryRuleCategory.BUSY).maxTotalAttempts()).isEqualTo(6);
        }

        @Test
        @DisplayName("VAL-18: hasRules() distinguishes 'no rules' from 'rules configured'")
        void hasRulesDistinguishes() {
            assertThat(new RetryPolicySpec(1, 60, RetryStrategy.FIXED).hasRules()).isFalse();
            assertThat(new RetryPolicySpec(1, 60, RetryStrategy.FIXED, List.of()).hasRules())
                    .isFalse();
            assertThat(new RetryPolicySpec(1, 60, RetryStrategy.FIXED,
                    List.of(rule(RetryRuleCategory.BUSY, Boolean.TRUE, 1, "01:00"))).hasRules())
                    .isTrue();
        }
    }
}
