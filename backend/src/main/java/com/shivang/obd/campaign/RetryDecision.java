package com.shivang.obd.campaign;

import java.time.Instant;

/**
 * The result of asking the campaign retry policy about one failed attempt
 * (VB-6D.2) — a small explicit value rather than scattered booleans.
 *
 * <p>Deterministic by construction: it is a pure function of the immutable
 * execution snapshot, the attempt's own number, the canonical failure, and the
 * instant the attempt failed. It performs no I/O — no counter is incremented,
 * no capacity reserved, no attempt created, no provider contacted. Those are
 * the orchestrator's job, and keeping them out is what makes the policy
 * testable and safe to evaluate concurrently.
 *
 * <p>A decision is always <em>explainable</em>: {@link #reason()} names the
 * single rule that produced it, so a retry that does or does not happen can be
 * traced to a cause without re-deriving the logic.
 */
public record RetryDecision(
        boolean retryable,
        FailureClassification classification,
        RetryRule rule,
        int attemptNumber,
        int nextAttemptNumber,
        int maxTotalAttempts,
        Instant nextEligibleAt,
        String reason) {

    /** No retry, and no category governed the outcome. */
    public static RetryDecision notRetryable(String reason) {
        return new RetryDecision(false, null, null, 0, 0, 0, null, reason);
    }

    /** A retry that may be created once {@link #nextEligibleAt()} has passed. */
    public static RetryDecision retryable(FailureClassification classification,
                                           RetryRule rule,
                                           int attemptNumber,
                                           int nextAttemptNumber,
                                           int maxTotalAttempts,
                                           Instant nextEligibleAt,
                                           String reason) {
        return new RetryDecision(true, classification, rule, attemptNumber, nextAttemptNumber,
                maxTotalAttempts, nextEligibleAt, reason);
    }
}
