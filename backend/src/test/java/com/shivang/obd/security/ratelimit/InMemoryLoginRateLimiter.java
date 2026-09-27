package com.shivang.obd.security.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic in-memory limiter for application-layer tests. Mirrors the
 * Redis contract: atomic increments, threshold blocking across all three
 * dimensions, and identical reset semantics (account+combined only).
 */
public class InMemoryLoginRateLimiter implements LoginRateLimiter {

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final int maxAttempts;

    public InMemoryLoginRateLimiter(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    @Override
    public LoginRateLimitDecision evaluate(LoginAttemptIdentity identity) {
        boolean exceeded =
            count(LoginRateLimitKeys.forIp(identity.ipAddress())) >= maxAttempts
            || count(LoginRateLimitKeys.forAccount(identity.normalizedEmail())) >= maxAttempts
            || count(LoginRateLimitKeys.forCombined(
                identity.ipAddress(), identity.normalizedEmail())) >= maxAttempts;
        return exceeded ? LoginRateLimitDecision.block(60) : LoginRateLimitDecision.allow();
    }

    @Override
    public void recordFailure(LoginAttemptIdentity identity) {
        increment(LoginRateLimitKeys.forIp(identity.ipAddress()));
        increment(LoginRateLimitKeys.forAccount(identity.normalizedEmail()));
        increment(LoginRateLimitKeys.forCombined(
            identity.ipAddress(), identity.normalizedEmail()));
    }

    @Override
    public void resetOnSuccessfulLogin(LoginAttemptIdentity identity) {
        counters.remove(LoginRateLimitKeys.forAccount(identity.normalizedEmail()));
        counters.remove(LoginRateLimitKeys.forCombined(
            identity.ipAddress(), identity.normalizedEmail()));
    }

    public int failureCountOnAccount(String normalizedEmail) {
        return count(LoginRateLimitKeys.forAccount(normalizedEmail));
    }

    private void increment(String key) {
        counters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private int count(String key) {
        return counters.getOrDefault(key, new AtomicInteger()).get();
    }
}
