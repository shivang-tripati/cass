package com.shivang.obd.campaign;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The single canonical domain validation rule for campaign retry policy
 * (VB-6D.2) — the service/entity-layer boundary of the one-rule,
 * three-boundaries pattern already used by {@code dailyDialLimit}.
 *
 * <p>Boundaries:
 * <ol>
 *   <li>API: {@code RetryRuleConfig} bean constraints bound the retry count and
 *       the {@code MM:SS} shape;</li>
 *   <li>domain (this class): called by {@code CampaignService.create/update}, so
 *       an entity built outside REST cannot carry an impossible policy — this
 *       also covers the rules that bean validation structurally cannot express
 *       (duplicate categories, an enabled rule with no allowance, a delay
 *       outside the product range);</li>
 *   <li>database: {@code ck_campaigns_retry_rules_array} /
 *       {@code ck_cec_retry_rules_array} (V49) assert the column is a JSON
 *       array, so a malformed shape can never be persisted.</li>
 * </ol>
 *
 * <p>Validating here rather than only at the DTO is not redundancy: the DTO
 * cannot express "two rules for the same category" or "an enabled rule whose
 * delay does not parse", and this class is the only place those are caught for
 * non-REST callers.
 */
public final class RetryPolicyValidator {

    private RetryPolicyValidator() {
    }

    /**
     * The service-layer entry point: validates the <em>API view</em> before it
     * is mapped.
     *
     * <p>This exists because a bean-validation {@code @Pattern} can only
     * describe the <em>shape</em> of {@code MM:SS}, not its range: "00:60"
     * matches {@code ^\d{2}:\d{2}$} perfectly and is still invalid. Parsing is
     * therefore done here, where a parse failure can be converted into the
     * platform's {@code VALIDATION_ERROR} contract. Without this, a
     * syntactically well-formed but out-of-range delay would surface as an
     * {@link IllegalArgumentException} — an HTTP 500 — instead of the 400 the
     * API contract promises.
     */
    public static void validateView(com.shivang.obd.campaign.dto.RetryPolicyConfig view) {
        if (view == null || view.rules() == null) {
            return;
        }
        for (com.shivang.obd.campaign.dto.RetryRuleConfig rule : view.rules()) {
            if (rule == null || rule.retryDelay() == null) {
                // Shape/absence of a delay is enforced by bean validation and
                // by validate(...) below; nothing to parse here.
                continue;
            }
            try {
                RetryDelay.parse(rule.retryDelay());
            } catch (IllegalArgumentException malformed) {
                throw invalid(malformed.getMessage());
            }
        }
    }

    /**
     * Validates a candidate policy. Null is accepted and means "no policy
     * configured" (the entity then stores the normalized no-retries default).
     *
     * @throws BusinessException (VALIDATION_ERROR) when the policy is impossible
     */
    public static void validate(RetryPolicySpec policy) {
        if (policy == null) {
            return;
        }

        // The flat allowance must be internally consistent: retries without a
        // delay would busy-loop a contact, so the interval is mandatory when
        // the flat allowance is positive. (Mirrors the pre-existing DB CHECK
        // ck_campaigns_retry_interval; validated here so a non-REST caller
        // cannot persist an inconsistent policy.)
        if (policy.getMaxAttempts() != null && policy.getMaxAttempts() < 0) {
            throw invalid("maxAttempts must be zero or greater, got "
                    + policy.getMaxAttempts());
        }
        if (policy.getMaxAttempts() != null && policy.getMaxAttempts() > 0
                && (policy.getIntervalSeconds() == null || policy.getIntervalSeconds() <= 0)) {
            throw invalid("maxAttempts=" + policy.getMaxAttempts()
                    + " requires a positive intervalSeconds");
        }

        List<RetryRule> rules = policy.getRules();
        if (rules == null || rules.isEmpty()) {
            return;
        }

        Set<RetryRuleCategory> seen = EnumSet.noneOf(RetryRuleCategory.class);
        for (RetryRule rule : rules) {
            if (rule == null) {
                throw invalid("retry rules must not contain a null entry");
            }
            if (rule.category() == null) {
                throw invalid("each retry rule requires a category");
            }
            if (!seen.add(rule.category())) {
                throw invalid("duplicate retry rule for category "
                        + rule.category().name() + "; configure at most one rule per category");
            }
            validateRule(rule);
        }
    }

    private static void validateRule(RetryRule rule) {
        int maxRetries = rule.effectiveMaxRetries();
        if (maxRetries < 0) {
            throw invalid("maxRetries must be zero or greater for category "
                    + rule.category().name() + ", got " + maxRetries);
        }
        if (maxRetries > RetryRule.MAX_RETRIES) {
            throw invalid("maxRetries must be " + RetryRule.MAX_RETRIES + " or less for category "
                    + rule.category().name() + ", got " + maxRetries);
        }

        boolean enabled = rule.isEnabled();
        RetryDelay delay = rule.retryDelay();

        // An enabled rule that permits retries must carry a delay; without one
        // the orchestrator would have to guess a cadence.
        if (enabled && maxRetries > 0 && delay == null) {
            throw invalid("retry rule for category " + rule.category().name()
                    + " is enabled with maxRetries=" + maxRetries
                    + " and therefore requires a retryDelay (MM:SS)");
        }
        // A rule that permits no retry must not carry a delay: it would be a
        // value that can never take effect, i.e. a misleading configuration.
        if (!enabled && delay != null) {
            throw invalid("retry rule for category " + rule.category().name()
                    + " is disabled and must not carry a retryDelay");
        }
        if (maxRetries == 0 && delay != null) {
            throw invalid("retry rule for category " + rule.category().name()
                    + " permits no retries (maxRetries=0) and must not carry a retryDelay");
        }
    }

    private static BusinessException invalid(String message) {
        // BusinessException(VALIDATION_ERROR) maps onto the platform's existing
        // 400 ProblemDetail contract at every boundary.
        return new BusinessException(CommonErrorCode.VALIDATION_ERROR, message);
    }
}
