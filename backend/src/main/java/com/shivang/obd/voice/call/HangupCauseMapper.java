package com.shivang.obd.voice.call;

import java.util.Locale;
import java.util.Map;

/**
 * The single deterministic boundary between a provider hangup cause and the
 * canonical business failure vocabulary (VB-6D.1).
 *
 * <p><b>Why this exists.</b> The cause arrived from the provider, but the
 * <em>meaning</em> is a campaign concern: {@code CallFailureCode} carries the
 * retry classification, and VB-6D will decide retries per canonical category.
 * Before this mapper, each consumer translated causes on its own, and both
 * translations ended in {@code "HANGUP_" + cause} — an unbounded,
 * provider-derived string that is <em>not</em> a canonical code. Because
 * {@code CallFailureCode.fromCode} returns empty for it, such a value slipped
 * past the taxonomy and inherited the "unknown ⇒ retryable" fallback, letting
 * a carrier's arbitrary text decide whether a contact gets re-dialled.</p>
 *
 * <p><b>The invariant this type establishes.</b> {@link #toFailureCode} is
 * total and closed: it returns one of a fixed set of canonical
 * {@code CallFailureCode} names, and <em>never</em> echoes, prefixes, or
 * otherwise derives from the provider string. An unrecognized, malformed, or
 * absent cause therefore always resolves to {@code HANGUP_UNKNOWN} — the
 * canonical "unrecognized or missing hangup cause" code — rather than to
 * {@code HANGUP_<provider text>}. There is no input for which this class
 * invents a business meaning.</p>
 *
 * <p><b>Placement.</b> Voice owns telephony-facing abstractions and Telephony
 * adapters implement voice-owned contracts, so a provider-cause normalizer
 * belongs here rather than in the campaign module. {@code campaign} owns the
 * <em>meaning</em> of the returned vocabulary; this class owns only the
 * translation. Keeping the translation in {@code voice.call} is what lets both
 * consumers share it without introducing a {@code campaign → voice} /
 * {@code voice → campaign} cycle.</p>
 *
 * <p><b>Deliberately not modelled.</b> Carrier-specific categories such as
 * switched-off or network-unreachable are <em>not</em> derived here. FreeSWITCH
 * surfaces them only as an unbounded, provider-dependent set of SIP/Q.850
 * causes whose spelling varies by carrier and gateway, so any mapping would be
 * a guess presented as a classification. They remain future policy categories;
 * today such causes deterministically resolve to {@code HANGUP_UNKNOWN}.</p>
 */
public final class HangupCauseMapper {

    /**
     * FreeSWITCH symbolic spelling of a normal release. Treated
     * case-insensitively, matching the pre-existing behaviour.
     */
    private static final String NORMAL_CLEARING = "NORMAL_CLEARING";

    /**
     * The only value returned for a cause that carries no usable
     * classification. Canonical: it is a real {@code CallFailureCode} constant,
     * so it can never be mistaken for an unknown code by a consumer.
     */
    public static final String UNKNOWN_CODE = "HANGUP_UNKNOWN";

    /**
     * Closed, total mapping of the failure-bearing causes this integration
     * actually supports, keyed by the numeric Q.850/SIP code <em>and</em> the
     * FreeSWITCH symbolic alias. A {@code Map} (not a {@code switch}) keeps the
     * set enumerable, so a test can assert exhaustively that every value is a
     * canonical {@code CallFailureCode} and that no key leaks provider text.
     *
     * <p>Cause 16 (normal clearing) is deliberately absent: it is a successful
     * completion, never a failure, and is answered by
     * {@link #isNormalClearing(String)} instead. It must never reach this table
     * as a failure — the pre-VB-6D.1 mappers returned a non-canonical
     * {@code "COMPLETED"} value for it on the agent-outbound path.
     */
    private static final Map<String, String> FAILURE_CAUSES = Map.ofEntries(
            Map.entry("17", "BUSY"),                     // Q.850 user busy
            Map.entry("USER_BUSY", "BUSY"),
            Map.entry("19", "NO_ANSWER"),                // Q.850 no answer
            Map.entry("NO_ANSWER", "NO_ANSWER"),
            Map.entry("21", "REJECTED"),                 // Q.850 call rejected
            Map.entry("CALL_REJECTED", "REJECTED"),
            Map.entry("34", "CONGESTION"),               // Q.850 no circuit available
            Map.entry("NO_CIRCUIT_AVAILABLE", "CONGESTION"),
            Map.entry("41", "TEMPORARY_FAILURE"),        // Q.850 temporary failure
            Map.entry("NORMAL_TEMPORARY_FAILURE", "TEMPORARY_FAILURE"),
            Map.entry("47", "RESOURCE_UNAVAILABLE"),     // Q.850 resource unavailable
            Map.entry("RESOURCE_UNAVAILABLE", "RESOURCE_UNAVAILABLE"));

    private HangupCauseMapper() {
    }

    /**
     * Whether the cause represents a normal release — the successful
     * completion of a call, which is an outcome and <em>not</em> a failure.
     * Both the numeric code and the symbolic alias are recognised, and the
     * symbolic form is matched case-insensitively as before.
     */
    public static boolean isNormalClearing(String cause) {
        return "16".equals(trimmed(cause))
                || NORMAL_CLEARING.equalsIgnoreCase(trimmed(cause));
    }

    /**
     * The canonical failure code for a provider hangup cause.
     *
     * <p>Total and closed: the return value is always a canonical
     * {@code CallFailureCode} name and never {@code null}. Any cause that is
     * absent, blank, unmapped, or provider-specific-but-unrecognised resolves
     * to {@link #UNKNOWN_CODE}. No provider-supplied text is ever propagated
     * into the returned value.
     *
     * @param cause the raw provider hangup cause; may be {@code null}
     * @return a canonical {@code CallFailureCode} name, never {@code null}
     */
    public static String toFailureCode(String cause) {
        String normalized = trimmed(cause);
        if (normalized == null) {
            return UNKNOWN_CODE;
        }
        String mapped = FAILURE_CAUSES.get(normalized.toUpperCase(Locale.ROOT));
        return mapped != null ? mapped : UNKNOWN_CODE;
    }

    /**
     * The closed set of canonical codes this mapper can emit. Exposed so tests
     * can assert exhaustively that no other value is reachable — the property
     * that makes "arbitrary provider text can never become a business failure
     * code" verifiable rather than merely intended.
     */
    public static java.util.Set<String> supportedFailureCodes() {
        java.util.Set<String> supported = new java.util.LinkedHashSet<>();
        supported.add(UNKNOWN_CODE);
        supported.addAll(FAILURE_CAUSES.values());
        return java.util.Collections.unmodifiableSet(supported);
    }

    private static String trimmed(String cause) {
        if (cause == null) {
            return null;
        }
        String value = cause.trim();
        return value.isEmpty() ? null : value;
    }
}
