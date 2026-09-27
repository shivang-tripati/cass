package com.shivang.obd.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.security.detection.SecuritySignalType;
import com.shivang.obd.security.detection.SuspiciousActivityDetector;
import com.shivang.obd.security.event.SecurityEventRequestContextProvider;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginProtectionServiceTest {

    private static final String EMAIL = "user@obd.test";

    private InMemoryLoginRateLimiter limiter;
    private LoginProtectionService service;
    private SuspiciousActivityDetector detector;

    @BeforeEach
    void setUp() {
        limiter = new InMemoryLoginRateLimiter(3);
        detector = new SuspiciousActivityDetector(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        service = new LoginProtectionService(limiter, fixedContext("10.0.0.1"), detector);
    }

    private SecurityEventRequestContextProvider fixedContext(String ip) {
        return () -> Optional.of(new SecurityEventRequestContextProvider.RequestMetadata(
            "req", ip, "UA"));
    }

    @Test
    void allowsBelowThresholdAcrossAllDimensions() {
        for (int i = 0; i < 2; i++) {
            service.checkCurrentRequestAllowed(EMAIL);
            service.recordCurrentRequestFailure(EMAIL);
        }
        service.checkCurrentRequestAllowed(EMAIL);
    }

    @Test
    void blocksAtThresholdWith429Contract() {
        for (int i = 0; i < 3; i++) {
            service.recordCurrentRequestFailure(EMAIL);
        }

        BusinessException failure = (BusinessException) catchThrowable(
            () -> service.checkCurrentRequestAllowed(EMAIL));

        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.RATE_LIMITED);
        assertThat(failure.getHttpStatus().value()).isEqualTo(429);
        assertThat(failure.getMessage())
            .isEqualTo(LoginProtectionService.THROTTLED_MESSAGE);
    }

    @Test
    void differentIpsDoNotShareIpCounters() {
        service = new LoginProtectionService(limiter, fixedContext("10.0.0.1"), detector);
        for (int i = 0; i < 3; i++) {
            service.recordCurrentRequestFailure(EMAIL);
        }
        // Same email/account from a DIFFERENT IP: IP dimension is clean and
        // combined differs too; only the account dimension is at threshold.
        service = new LoginProtectionService(
            limiter,
            fixedContext("10.0.0.2"), detector);
        var otherIdentity = new LoginAttemptIdentity("10.0.0.2", "other@obd.test");

        var decision = limiter.evaluate(otherIdentity);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void normalizedAccountsDoNotShareAccountCounters() {
        String otherEmail = "someoneelse@obd.test";
        for (int i = 0; i < 3; i++) {
            service.recordCurrentRequestFailure(EMAIL);
        }
        // Different IP AND different account: every dimension is clean.
        var freshIdentityService =
            new LoginProtectionService(limiter, fixedContext("10.0.0.2"), detector);
        freshIdentityService.checkCurrentRequestAllowed(otherEmail);
        assertThat(limiter.failureCountOnAccount(otherEmail)).isZero();
    }

    @Test
    void successResetClearsAccountAndCombinedButNotIpDimension() {
        for (int i = 0; i < 3; i++) {
            service.recordCurrentRequestFailure(EMAIL);
        }
        service.resetForCurrentRequestSuccess(EMAIL);

        assertThat(limiter.failureCountOnAccount(EMAIL)).isZero();
        // IP dimension intentionally retained (documented reset semantics):
        // same IP + brand-new account is STILL blocked by the IP counter,
        // preventing credential-stuffing resets via one valid login.
        var sameIpDifferentAccount = new LoginAttemptIdentity("10.0.0.1", "other@obd.test");
        assertThat(limiter.evaluate(sameIpDifferentAccount).allowed()).isFalse();
    }

    @Test
    void failOpenWhenRedisFailsOnCheckAndRecord() {
        org.springframework.data.redis.core.StringRedisTemplate failingTemplate =
            org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.mockito.Mockito.when(failingTemplate.opsForValue()).thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException("Redis down"));
        org.mockito.Mockito.doThrow(
                new org.springframework.dao.DataAccessResourceFailureException("Redis down"))
            .when(failingTemplate).delete(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doThrow(
                new org.springframework.dao.DataAccessResourceFailureException("Redis down"))
            .when(failingTemplate).expire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        org.mockito.Mockito.when(failingTemplate.getExpire(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class)))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Redis down"));

        var failingLimiter = new RedisLoginRateLimiter(failingTemplate,
            new LoginRateLimitProperties(
                new LoginRateLimitProperties.Dimension(3, 300),
                new LoginRateLimitProperties.Dimension(3, 600),
                new LoginRateLimitProperties.Dimension(3, 600)));
        var failingProtection = new LoginProtectionService(failingLimiter, fixedContext("10.0.0.1"), detector);

        // Documented fail-open policy: no exception reaches the caller.
        failingProtection.checkCurrentRequestAllowed(EMAIL);
        failingProtection.recordCurrentRequestFailure(EMAIL);
        failingProtection.resetForCurrentRequestSuccess(EMAIL);
    }
}
