package com.shivang.obd.security.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application-facing API for recording security events. Callers never touch
 * entities or persistence. Request metadata (requestId/IP/user-agent) is
 * captured server-side; metadata maps are bounded and sanitized.
 *
 * <p>Transaction strategy: failure-path events use REQUIRES_NEW because
 * their callers throw generic authentication errors whose rollback must not
 * erase the record (mirrors the compromise-revocation pattern). Success-path
 * events join the caller's transaction and commit with it.</p>
 */
@Service
public class SecurityEventService {

    static final int MAX_METADATA_ENTRIES = 10;
    static final int MAX_KEY_LENGTH = 50;
    static final int MAX_VALUE_LENGTH = 200;

    private final SecurityEventRepository repository;
    private final SecurityEventRequestContextProvider requestContext;

    public SecurityEventService(
        SecurityEventRepository repository,
        SecurityEventRequestContextProvider requestContext
    ) {
        this.repository = repository;
        this.requestContext = requestContext;
    }

    public void recordLoginSuccess(UUID userId) {
        recordLoginSuccess(userId, Map.of());
    }

    public void recordLoginSuccess(UUID userId, Map<String, String> metadata) {
        persist(SecurityEventType.LOGIN_SUCCESS, userId, true, metadata);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailure(UUID userIdOrUnknown) {
        recordLoginFailure(userIdOrUnknown, Map.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailure(UUID userIdOrUnknown, Map<String, String> metadata) {
        persist(SecurityEventType.LOGIN_FAILURE, userIdOrUnknown, false, metadata);
    }

    public void recordLogout(UUID userId) {
        recordLogout(userId, Map.of());
    }

    public void recordLogout(UUID userId, Map<String, String> metadata) {
        persist(SecurityEventType.LOGOUT, userId, true, metadata);
    }

    public void recordLogoutAll(UUID userId) {
        recordLogoutAll(userId, Map.of());
    }

    public void recordLogoutAll(UUID userId, Map<String, String> metadata) {
        persist(SecurityEventType.LOGOUT_ALL, userId, true, metadata);
    }

    public void recordTokenRefresh(UUID userId) {
        recordTokenRefresh(userId, Map.of());
    }

    public void recordTokenRefresh(UUID userId, Map<String, String> metadata) {
        persist(SecurityEventType.TOKEN_REFRESH, userId, true, metadata);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTokenReuseDetected(UUID userIdOrUnknown) {
        persist(SecurityEventType.TOKEN_REUSE_DETECTED, userIdOrUnknown, false, Map.of());
    }

    public void recordPasswordChanged(UUID userId) {
        recordPasswordChanged(userId, Map.of());
    }

    public void recordPasswordChanged(UUID userId, Map<String, String> metadata) {
        persist(SecurityEventType.PASSWORD_CHANGED, userId, true, metadata);
    }

    /** Bounded, sanitized event construction for controlled internal use. */
    SecurityEventEntity buildEvent(
        SecurityEventType eventType, UUID userId, boolean success, Map<String, String> metadata
    ) {
        SecurityEventEntity entity = new SecurityEventEntity();
        entity.setEventType(eventType);
        entity.setUserId(userId);
        entity.setSuccess(success);
        entity.setOccurredAt(Instant.now());
        entity.setMetadata(sanitize(metadata));
        requestContext.current().ifPresent(ctx -> {
            entity.setRequestId(ctx.requestId());
            entity.setIpAddress(ctx.ipAddress());
            entity.setUserAgent(ctx.userAgent());
        });
        return entity;
    }

    private void persist(
        SecurityEventType eventType, UUID userId, boolean success, Map<String, String> metadata
    ) {
        repository.save(buildEvent(eventType, userId, success, metadata));
    }

    /**
     * Defense-in-depth against credential/token leakage through metadata:
     * sensitive key names are dropped entirely, entries are capped in count
     * and length, and insertion order is preserved.
     */
    static Map<String, String> sanitize(Map<String, String> metadata) {
        Map<String, String> bounded = new LinkedHashMap<>();
        if (metadata == null) {
            return bounded;
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (bounded.size() >= MAX_METADATA_ENTRIES) {
                break;
            }
            String key = truncate(sanitizeValue(entry.getKey()), MAX_KEY_LENGTH);
            if (key.isBlank() || SENSITIVE_KEY_PATTERN.matcher(key).matches()) {
                continue;
            }
            bounded.put(key, truncate(sanitizeValue(entry.getValue()), MAX_VALUE_LENGTH));
        }
        return bounded;
    }

    private static final java.util.regex.Pattern SENSITIVE_KEY_PATTERN =
        java.util.regex.Pattern.compile("(?i).*(password|token|hash|secret|authorization).*");

    private static String sanitizeValue(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ");
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
