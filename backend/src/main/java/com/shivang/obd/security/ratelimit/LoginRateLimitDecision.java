package com.shivang.obd.security.ratelimit;

public record LoginRateLimitDecision(boolean allowed, long retryAfterSeconds) {

    public static LoginRateLimitDecision allow() {
        return new LoginRateLimitDecision(true, 0);
    }

    public static LoginRateLimitDecision block(long retryAfterSeconds) {
        return new LoginRateLimitDecision(false, Math.max(retryAfterSeconds, 1));
    }
}
