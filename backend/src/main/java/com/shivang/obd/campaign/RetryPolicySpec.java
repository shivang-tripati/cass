package com.shivang.obd.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Campaign-owned retry policy value object. Policy only — the execution engine
 * owns applying it to failed call attempts.
 *
 * <p><b>Flat (pre-VB-6D.2) fields.</b> {@code maxAttempts}, {@code intervalSeconds}
 * and {@code strategy} are the original single-allowance model and are
 * retained unchanged. {@code maxAttempts} counts <em>retries</em>, so the
 * maximum number of attempts is {@code 1 + maxAttempts}. Always present and
 * normalized: {@code maxAttempts == 0} means "no retries" with a null interval;
 * strategy defaults to {@link RetryStrategy#FIXED}.
 *
 * <p><b>Per-category rules (VB-6D.2).</b> {@link #rules} is an optional,
 * bounded list of {@link RetryRule} entries keyed by
 * {@link RetryRuleCategory}. It is {@code null} or empty for every campaign
 * created before this phase, and in that case the flat fields alone govern —
 * so a campaign's existing retry behaviour is unchanged by adopting the
 * feature. When rules are present they take precedence for their category, and
 * any category without an explicit rule falls back to the flat allowance.
 *
 * <p>Rules are only ever able to <em>restrict</em> retries: a
 * {@link RetryRule} cannot make a permanent failure retryable. See
 * {@link RetryPolicyService} for the evaluation order that guarantees it.
 *
 * <p>Stored as a JSONB document rather than a relational child table: the rule
 * set is a small bounded group (at most one per category) that is always read
 * and written as a single unit with the campaign, is never queried relationally,
 * and must be frozen verbatim into the execution snapshot. Two tables would add
 * a second persistence model and a second join to express a value that is
 * inherently one aggregate.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Embeddable
public class RetryPolicySpec {

    @Column(name = "retry_max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "retry_interval_seconds")
    private Integer intervalSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "retry_strategy", nullable = false, length = 20)
    private RetryStrategy strategy;

    /**
     * Optional per-category rules. {@code null} for pre-VB-6D.2 campaigns, in
     * which case the flat fields above govern entirely.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retry_rules")
    private List<RetryRule> rules;

    /**
     * The pre-VB-6D.2 shape: a flat allowance with no per-category rules.
     * Retained so every existing construction site keeps its exact previous
     * meaning — a policy built this way is governed entirely by
     * {@code maxAttempts}/{@code intervalSeconds}.
     */
    public RetryPolicySpec(Integer maxAttempts, Integer intervalSeconds, RetryStrategy strategy) {
        this(maxAttempts, intervalSeconds, strategy, null);
    }

    /**
     * The rule governing {@code category}: the explicit rule when one is
     * configured, otherwise the flat allowance expressed as a rule. A flat
     * allowance of zero yields a disabled rule rather than a permissive one, so
     * "no retries configured" keeps meaning exactly that.
     */
    public RetryRule ruleFor(RetryRuleCategory category) {
        if (rules != null) {
            for (RetryRule rule : rules) {
                if (rule != null && rule.category() == category) {
                    return rule;
                }
            }
        }
        return flatAsRule(category);
    }

    /** The flat allowance rendered as a rule, for a category with no override. */
    private RetryRule flatAsRule(RetryRuleCategory category) {
        int retries = maxAttempts == null ? 0 : maxAttempts;
        if (retries <= 0) {
            return RetryRule.disabled(category);
        }
        RetryDelay delay = intervalSeconds == null
                ? null
                : RetryDelay.ofSeconds(intervalSeconds);
        return new RetryRule(category, Boolean.TRUE, retries, delay);    }

    /** Whether any per-category rule is configured at all. */
    public boolean hasRules() {
        return rules != null && !rules.isEmpty();
    }
}
