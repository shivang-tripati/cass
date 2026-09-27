package com.shivang.obd.campaign;

import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * The single authority for "may this failed attempt be retried, and when?"
 * (VB-6D.2).
 *
 * <p><b>Pure by design.</b> Every method here is a total function of its
 * arguments and performs no I/O: it does not read the live campaign, increment
 * a counter, reserve capacity, create an attempt, or contact a provider. The
 * execution engine (the existing {@code @Scheduled} tick) applies the returned
 * decision. That separation is what makes the policy deterministically testable
 * and safe to evaluate from concurrent scheduler invocations, and it is why no
 * distributed lock is needed <em>for the policy itself</em>.
 *
 * <p><b>Configuration source.</b> Policy always comes from the execution's
 * immutable snapshot ({@link RetryPolicySpec}), never from the mutable
 * campaign. Editing a campaign after an execution exists therefore cannot
 * change that execution's retry behaviour.
 *
 * <p><b>Evaluation order — this is the safety contract.</b> Checks are applied
 * most-fundamental first, so no campaign configuration can ever talk a later
 * stage into permitting a retry:
 *
 * <pre>
 *   1. canonicalize the code        (no arbitrary provider string decides)
 *   2. pre-dispatch rejection?       -&gt; no retry, and no budget consumed
 *   3. permanent (canonical class)?  -&gt; no retry, ALWAYS
 *   4. category rule enabled?        -&gt; no retry
 *   5. retries remaining?            -&gt; no retry
 *   6. retry
 * </pre>
 *
 * Step 3 is the hard floor: {@code effectiveRetry = policyAllowsRetry AND
 * failureIsRetryEligible}, never an OR. A campaign can switch a category off
 * or lower its allowance, but it can never promote a permanent failure.
 *
 * <p><b>Delay.</b> The next eligible instant is {@code failedAt + rule.delay},
 * a timezone-independent {@link Instant} operation. Deliberately <em>not</em>
 * clamped to the campaign's calling hours here: the snapshot's schedule window
 * is a separate concern owned by the orchestrator, and folding it in would
 * make the delay impossible to reason about in isolation.
 */
@Service
public class RetryPolicyService {

    /** Default delay when a legacy campaign enables retries without an interval. */
    static final int LEGACY_DEFAULT_DELAY_SECONDS = 60;

    /**
     * Decides whether the failed attempt may be retried.
     *
     * @param policy          the execution's immutable retry policy
     * @param failureCode     the persisted failure code (canonicalized here)
     * @param attemptNumber   the failed attempt's own number, 1-based
     * @param failedAt        when the attempt failed; null falls back to "now"
     * @return a deterministic, explainable decision
     */
    public RetryDecision evaluate(RetryPolicySpec policy,
                                  String failureCode,
                                  int attemptNumber,
                                  Instant failedAt) {

        // 1. Canonicalize first: an arbitrary provider string must never be able
        //    to influence this decision. VB-6D.1's boundary makes the value
        //    total, so this cannot fail.
        FailureClassification classification = FailureClassification.of(failureCode);
        CallFailureCode canonical = CallFailureCode.canonicalize(failureCode);

        // 2. The call was never placed. Nothing to retry, and — critically — no
        //    retry budget is spent, because a requeue (capacity, provider
        //    unavailable) never reaches here at all.
        if (classification.isPreDispatch()) {
            return RetryDecision.notRetryable(
                    "PRE_DISPATCH_REJECTION: " + canonical.name()
                            + " means no call was placed; retry policy does not govern it"
                            + " and no retry budget is consumed");
        }

        // 3. The hard floor. A permanent outcome is never retryable, whatever
        //    the campaign configures.
        if (canonical.getRetryClass() == CallFailureCode.RetryClass.PERMANENT) {
            return RetryDecision.notRetryable(
                    "PERMANENT_FAILURE: " + canonical.name()
                            + " is a permanent outcome; campaign policy cannot make it retryable");
        }

        RetryRuleCategory category = classification.category();
        RetryRule rule = policy == null
                ? RetryRule.disabled(category)
                : policy.ruleFor(category);

        // 4. The campaign may switch a category off entirely.
        if (!rule.isEnabled() || rule.maxRetries() <= 0) {
            return RetryDecision.notRetryable(
                    "RULE_DISABLED: no retries configured for category " + category.name());
        }

        // 5. Retries remaining. maxRetries counts retries, so the last permitted
        //    attempt number is 1 + maxRetries.
        int nextAttemptNumber = attemptNumber + 1;
        int maxTotalAttempts = rule.maxTotalAttempts();
        if (nextAttemptNumber > maxTotalAttempts) {
            return RetryDecision.notRetryable(
                    "RETRIES_EXHAUSTED: attempt " + attemptNumber + " of "
                            + maxTotalAttempts + " for category " + category.name());
        }

        // 6. Retry. Delay resolution is deterministic and timezone-independent.
        RetryDelay delay = rule.retryDelay() != null
                ? rule.retryDelay()
                : RetryDelay.ofSeconds(LEGACY_DEFAULT_DELAY_SECONDS);
        Instant base = failedAt != null ? failedAt : Instant.now();
        return RetryDecision.retryable(classification, rule, attemptNumber, nextAttemptNumber,
                maxTotalAttempts, delay.applyTo(base),
                "RETRY_ALLOWED: category " + category.name() + ", failure " + canonical.name()
                        + ", retry " + nextAttemptNumber + " of " + maxTotalAttempts
                        + " after " + delay);
    }
}
