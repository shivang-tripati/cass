package com.shivang.obd.contact;

import java.util.regex.Pattern;

/**
 * Single source of the contact field contracts shared by the API layer
 * (Jakarta annotations), the bulk-import pipeline and the database CHECK
 * constraint. Do not duplicate these expressions elsewhere.
 */
public final class ContactValidation {

    /** Canonical E.164 number, e.g. "+918012345678". */
    public static final String E164_REGEX = "^\\+[1-9][0-9]{6,14}$";
    public static final Pattern E164 = Pattern.compile(E164_REGEX);

    /** Formatting separators tolerated inside bulk-file phone values. */
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s().\\-]");

    /** Pragmatic email shape consistent with the API-layer @Email rule. */
    public static final String EMAIL_REGEX = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";
    public static final Pattern EMAIL = Pattern.compile(EMAIL_REGEX);

    /**
     * Deterministic canonicalization for bulk-import phone values:
     * strips formatting separators (spaces, dashes, dots, parentheses)
     * and then applies the existing canonical E.164 contract. The
     * canonical form is what gets deduplicated and persisted; input that
     * cannot reach canonical form yields {@code null} and stays a
     * validation error. This adds no new identity rule — the E.164
     * contract above remains the single authority.
     */
    public static String canonicalizePhoneNumber(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String candidate = PHONE_SEPARATORS.matcher(rawValue.trim()).replaceAll("");
        return E164.matcher(candidate).matches() ? candidate : null;
    }

    private ContactValidation() {
    }
}
