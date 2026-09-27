package com.shivang.obd.security.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Centralized, versioned Redis key construction. Sensitive components
 * (email, IP) are SHA-256 digested — no raw values ever enter key names,
 * and keys never contain passwords or token material.
 */
public final class LoginRateLimitKeys {

    private static final String PREFIX = "obd:security:login:v1";
    private static final String IP = "ip";
    private static final String ACCOUNT = "account";
    private static final String COMBINED = "combined";

    private LoginRateLimitKeys() {
    }

    public static String forIp(String ipAddress) {
        return PREFIX + ":" + IP + ":" + digest(ipAddress);
    }

    public static String forAccount(String normalizedEmail) {
        return PREFIX + ":" + ACCOUNT + ":" + digest(normalizedEmail);
    }

    public static String forCombined(String ipAddress, String normalizedEmail) {
        return PREFIX + ":" + COMBINED + ":" + digest(ipAddress) + ":" + digest(normalizedEmail);
    }

    public static String digest(String component) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                md.digest((component == null ? "" : component)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
