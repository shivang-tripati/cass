package com.shivang.obd.security.ratelimit;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.security.detection.SecuritySignal;
import com.shivang.obd.security.detection.SecuritySignalSeverity;
import com.shivang.obd.security.detection.SecuritySignalType;
import com.shivang.obd.security.detection.SuspiciousActivityDetector;
import com.shivang.obd.security.event.SecurityEventRequestContextProvider;
import org.springframework.stereotype.Service;

/**
 * Application-facing login protection. Resolves the client IP server-side
 * (never from client-asserted headers) and delegates dimension evaluation to
 * the configured {@link LoginRateLimiter}. Redis details never leak past
 * this layer. Blocked requests additionally emit an
 * EXCESSIVE_LOGIN_FAILURES suspicious-activity signal.
 */
@Service
public class LoginProtectionService {

    static final String THROTTLED_MESSAGE = "Too many requests. Try again later.";

    private final LoginRateLimiter rateLimiter;
    private final SecurityEventRequestContextProvider requestContext;
    private final SuspiciousActivityDetector suspiciousActivityDetector;

    public LoginProtectionService(
        LoginRateLimiter rateLimiter,
        SecurityEventRequestContextProvider requestContext,
        SuspiciousActivityDetector suspiciousActivityDetector
    ) {
        this.rateLimiter = rateLimiter;
        this.requestContext = requestContext;
        this.suspiciousActivityDetector = suspiciousActivityDetector;
    }

    public void checkCurrentRequestAllowed(String normalizedEmail) {
        LoginAttemptIdentity identity = currentIdentity(normalizedEmail);
        LoginRateLimitDecision decision = rateLimiter.evaluate(identity);
        if (!decision.allowed()) {
            suspiciousActivityDetector.process(SecuritySignal.of(
                SecuritySignalType.EXCESSIVE_LOGIN_FAILURES,
                SecuritySignalSeverity.HIGH, null, null));
            throw new BusinessException(CommonErrorCode.RATE_LIMITED, THROTTLED_MESSAGE);
        }
    }

    public void recordCurrentRequestFailure(String normalizedEmail) {
        rateLimiter.recordFailure(currentIdentity(normalizedEmail));
    }

    public void resetForCurrentRequestSuccess(String normalizedEmail) {
        rateLimiter.resetOnSuccessfulLogin(currentIdentity(normalizedEmail));
    }

    private LoginAttemptIdentity currentIdentity(String normalizedEmail) {
        String ip = requestContext.current()
            .map(SecurityEventRequestContextProvider.RequestMetadata::ipAddress)
            .orElse(null);
        return new LoginAttemptIdentity(ip, normalizedEmail);
    }
}
