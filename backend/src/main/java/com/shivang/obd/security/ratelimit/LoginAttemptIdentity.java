package com.shivang.obd.security.ratelimit;

/**
 * Login attempt coordinates. Email must already be normalized upstream;
 * the infrastructure layer derives digests for Redis keys.
 */
public record LoginAttemptIdentity(String ipAddress, String normalizedEmail) {
}
