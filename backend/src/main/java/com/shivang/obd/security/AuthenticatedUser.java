package com.shivang.obd.security;

import java.util.UUID;

/**
 * Application-level authenticated identity. sessionId carries the active
 * refresh-token family (the session); it is an identifier, never a
 * credential, and is null for legacy tokens issued before Phase 2B.2.
 */
public record AuthenticatedUser(UUID userId, String email, UUID sessionId) {
}
