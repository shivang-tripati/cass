package com.shivang.obd.security.ratelimit;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed limiter. Concurrency: counters use atomic INCR; the TTL is
 * applied when the counter is created (first increment), so windows are
 * fixed-length from first failure and keys cannot grow unbounded.
 *
 * <p>Fail-open policy: Redis unavailability must not take login down for
 * the whole platform, so infrastructure failures are logged with safe
 * derived identifiers and treated as "allowed / nothing recorded".</p>
 */
public class RedisLoginRateLimiter implements LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginRateLimiter.class);

    private final StringRedisTemplate redisTemplate;
    private final LoginRateLimitProperties properties;

    public RedisLoginRateLimiter(StringRedisTemplate redisTemplate, LoginRateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public LoginRateLimitDecision evaluate(LoginAttemptIdentity identity) {
        try {
            long worstRetryAfter = 0;
            if (isBlocked(LoginRateLimitKeys.forIp(identity.ipAddress()),
                properties.ip().maxAttempts())) {
                worstRetryAfter = Math.max(worstRetryAfter,
                    remainingSeconds(LoginRateLimitKeys.forIp(identity.ipAddress())));
            }
            if (isBlocked(LoginRateLimitKeys.forAccount(identity.normalizedEmail()),
                properties.account().maxAttempts())) {
                worstRetryAfter = Math.max(worstRetryAfter,
                    remainingSeconds(LoginRateLimitKeys.forAccount(identity.normalizedEmail())));
            }
            if (isBlocked(
                LoginRateLimitKeys.forCombined(identity.ipAddress(), identity.normalizedEmail()),
                properties.combined().maxAttempts())) {
                String combinedKey =
                    LoginRateLimitKeys.forCombined(identity.ipAddress(), identity.normalizedEmail());
                worstRetryAfter = Math.max(worstRetryAfter, remainingSeconds(combinedKey));
            }
            return worstRetryAfter > 0
                ? LoginRateLimitDecision.block(worstRetryAfter)
                : LoginRateLimitDecision.allow();
        } catch (RuntimeException ex) {
            log.warn("Login rate limiting unavailable (fail-open) at {}", Instant.now(), ex);
            return LoginRateLimitDecision.allow();
        }
    }

    @Override
    public void recordFailure(LoginAttemptIdentity identity) {
        try {
            recordFailureOnKey(LoginRateLimitKeys.forIp(identity.ipAddress()),
                properties.ip().windowSeconds());
            recordFailureOnKey(LoginRateLimitKeys.forAccount(identity.normalizedEmail()),
                properties.account().windowSeconds());
            recordFailureOnKey(
                LoginRateLimitKeys.forCombined(identity.ipAddress(), identity.normalizedEmail()),
                properties.combined().windowSeconds());
        } catch (RuntimeException ex) {
            log.warn("Failed-attempt recording unavailable (fail-open) at {}", Instant.now(), ex);
        }
    }

    @Override
    public void resetOnSuccessfulLogin(LoginAttemptIdentity identity) {
        try {
            // IP dimension is intentionally NOT reset: clearing it on any
            // successful login would let an attacker holding valid account
            // credentials keep brute-forcing other accounts from the same IP.
            redisTemplate.delete(LoginRateLimitKeys.forAccount(identity.normalizedEmail()));
            redisTemplate.delete(
                LoginRateLimitKeys.forCombined(identity.ipAddress(), identity.normalizedEmail()));
        } catch (RuntimeException ex) {
            log.warn("Success-reset unavailable (fail-open) at {}", Instant.now(), ex);
        }
    }

    private boolean isBlocked(String key, int maxAttempts) {
        String count = redisTemplate.opsForValue().get(key);
        return count != null && Long.parseLong(count) >= maxAttempts;
    }

    /**
     * Atomic INCR + conditional TTL in a single Lua script: closes the
     * crash window between INCR and EXPIRE that could leave a key without
     * a window (unbounded growth / permanent lockout risk).
     */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> INCREMENT_WITH_TTL_SCRIPT =
        org.springframework.data.redis.core.script.RedisScript.of(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private void recordFailureOnKey(String key, long windowSeconds) {
        Long count = redisTemplate.execute(
            INCREMENT_WITH_TTL_SCRIPT,
            java.util.List.of(key),
            String.valueOf(windowSeconds * 1000));
        if (log.isTraceEnabled() && count != null) {
            log.trace("Rate-limit counter [digestKey] incremented to {}", count);
        }
    }

    private long remainingSeconds(String key) {
        Long ttl = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        return ttl == null || ttl < 1 ? 1 : ttl;
    }
}
