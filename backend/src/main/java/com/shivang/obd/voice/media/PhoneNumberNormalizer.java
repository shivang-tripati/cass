package com.shivang.obd.voice.media;

/**
 * Canonical E.164 normalization — trim, remove spaces/dashes/parentheses.
 */
public final class PhoneNumberNormalizer {

    private PhoneNumberNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("[\\s\\-\\(\\)\\.]+", "");
        if (!s.startsWith("+")) {
            // assume already E.164 or missing + — keep as is for validation to reject
            if (s.matches("[1-9][0-9]{6,14}")) s = "+" + s;
        }
        return s;
    }

    public static boolean isValidE164(String normalized) {
        return normalized != null && normalized.matches("^\\+[1-9][0-9]{6,14}$");
    }
}
