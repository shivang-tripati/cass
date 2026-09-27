package com.shivang.obd.campaign;

import java.time.Duration;

/**
 * One campaign-configured retry rule: what to do for a single
 * {@link RetryRuleCategory} (VB-6D.2).
 *
 * <p><b>maxRetries is not maxAttempts.</b> {@code maxRetries = 2} means
 * <em>two retries</em> beyond the initial attempt, i.e. up to three attempts in
 * total ({@code maxTotalAttempts = 1 + maxRetries}). This is the distinction
 * the whole phase turns on, and it is preserved verbatim by the conversion to
 * the pre-existing flat policy: a legacy campaign with
 * {@code retry_max_attempts = 2} keeps exactly two retries.
 *
 * <p><b>Rules can only restrict.</b> A rule may switch a category off
 * ({@code enabled = false}) or lower its allowance, but it can never make a
 * {@link CallFailureCode.RetryClass#PERMANENT} outcome retryable, nor force a
 * retry for a {@link FailureClassification.Disposition#PRE_DISPATCH}
 * rejection. That is enforced by the evaluation order in
 * {@link RetryPolicyService}, not by convention at the call sites.
 *
 * <p>Immutable and JSON-persistable: this is the form stored in the campaign
 * and frozen into the execution snapshot.
 */
public record RetryRule(
        RetryRuleCategory category,
        Boolean enabled,
        Integer maxRetries,
        RetryDelay retryDelay) {

    /** Upper bound on retries for a single rule; mirrors the legacy 0–10 column. */
    public static final int MAX_RETRIES = 10;

    /**
     * A rule that permits no retry for its category. Used both for an explicit
     * "off" switch and as the value a category resolves to when a campaign
     * configures nothing for it and the default is disabled.
     */
    public static RetryRule disabled(RetryRuleCategory category) {
        return new RetryRule(category, Boolean.FALSE, 0, null);
    }

    /** A permissive rule: {@code maxRetries} retries, each after {@code delay}. */
    public static RetryRule of(RetryRuleCategory category, int maxRetries, Duration delay) {
        return new RetryRule(category, Boolean.TRUE, maxRetries, RetryDelay.ofSeconds(delay.getSeconds()));
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * The permitted retries, defaulting to 0 when unset. Named distinctly from
     * the record accessor so the null-handling is visible at the call site.
     */
    public int effectiveMaxRetries() {
        return maxRetries == null ? 0 : maxRetries;
    }

    /** Maximum attempts this rule can produce: the initial attempt plus retries. */
    public int maxTotalAttempts() {
        return 1 + effectiveMaxRetries();
    }

    /**
     * The rule's contribution as a {@link RetryPolicySpec}: the legacy flat
     * shape, so existing consumers (the execution snapshot columns, the
     * pre-VB-6D orchestrator arithmetic) keep working unchanged.
     */
    public RetryPolicySpec toFlatPolicy() {
        return new RetryPolicySpec(effectiveMaxRetries(), retryDelaySeconds(), RetryStrategy.FIXED);
    }

    private Integer retryDelaySeconds() {
        return retryDelay == null ? null : (int) retryDelay.value().getSeconds();
    }
}
