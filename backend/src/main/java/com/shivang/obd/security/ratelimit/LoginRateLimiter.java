package com.shivang.obd.security.ratelimit;

public interface LoginRateLimiter {

    /** Evaluates all configured dimensions; returns blocked-with-retryAfter or allowed. */
    LoginRateLimitDecision evaluate(LoginAttemptIdentity identity);

    /** Records one failed attempt across all dimensions. */
    void recordFailure(LoginAttemptIdentity identity);

    /**
     * Clears failure state after successful authentication. Implementations
     * must NOT clear the IP dimension: a valid login from one account must
     * not allow an attacker sharing that IP to keep brute-forcing others.
     */
    void resetOnSuccessfulLogin(LoginAttemptIdentity identity);
}
