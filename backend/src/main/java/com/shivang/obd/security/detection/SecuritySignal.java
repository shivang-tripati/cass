package com.shivang.obd.security.detection;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Structured, bounded suspicious-activity signal produced by existing
 * security flows. Carries no credential/token material; identifiers are
 * server-derived. userId/familyId are nullable (pre-authentication signals
 * cannot resolve them).
 */
public record SecuritySignal(
    SecuritySignalType type,
    SecuritySignalSeverity severity,
    UUID userId,
    UUID familyId,
    Instant occurredAt,
    Map<String, String> metadata
) {

    public static SecuritySignal of(
        SecuritySignalType type,
        SecuritySignalSeverity severity,
        UUID userId,
        UUID familyId
    ) {
        return new SecuritySignal(type, severity, userId, familyId, Instant.now(), Map.of());
    }
}
