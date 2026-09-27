package com.shivang.obd.campaign;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic, timezone-independent retry delay expressed in the product's
 * {@code MM:SS} form (VB-6D.2).
 *
 * <p><b>Why a value object rather than a raw column.</b> The pre-existing
 * campaign column {@code retry_interval_seconds} is a bare integer, so the API
 * accepted {@code intervalSeconds: 90} and the UI had to guess how to render
 * it. The product requirement is {@code MM:SS}; representing that explicitly
 * means the wire format, the stored form, and the rendered form cannot drift,
 * and an unparseable value is rejected at the boundary instead of reaching the
 * dial path.
 *
 * <p><b>Semantics.</b> A delay is an <em>offset added to the moment the
 * attempt failed</em>. It carries no calendar, no timezone, and no schedule
 * knowledge: {@code failedAt.plus(delay)} is an {@code Instant} operation and
 * is therefore identical wherever the JVM or database runs. Keeping the
 * schedule window out of this type is deliberate — clamping a retry into the
 * campaign's calling hours is a snapshot concern owned by the execution
 * orchestrator, and mixing the two would make the delay untestable in
 * isolation.
 *
 * <p><b>Bounds.</b> {@code MM} is 00–99 and {@code SS} is 00–59, so a delay is
 * at most 99:59 (5999 s ≈ 100 minutes). This is deliberately narrower than the
 * legacy {@code retry_interval_seconds} ceiling of 604800 (7 days): a delay
 * measured in days is not a retry delay, it is a scheduling decision, and the
 * campaign schedule already expresses "come back tomorrow".
 */
public record RetryDelay(Duration value) {

    /** Product wire/storage form: two-digit minutes, colon, two-digit seconds. */
    private static final Pattern MM_SS = Pattern.compile("^(\\d{2}):(\\d{2})$");

    public static final int MAX_MINUTES = 99;
    public static final int MAX_SECONDS = 59;

    /** The shortest permitted delay; a zero delay would busy-loop a contact. */
    public static final Duration MIN = Duration.ofSeconds(1);

    /** The longest permitted delay, {@code 99:59}. */
    public static final Duration MAX = Duration.ofMinutes(MAX_MINUTES).plusSeconds(MAX_SECONDS);

    /**
     * Parses and validates the {@code MM:SS} product form.
     *
     * @param raw the wire value, e.g. {@code "00:30"} or {@code "05:00"}
     * @throws IllegalArgumentException if the form, or the resulting range, is
     *         invalid — the message is the user-facing reason
     */
    public static RetryDelay parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "retryDelay is required and must use the MM:SS format, e.g. \"05:00\"");
        }
        Matcher matcher = MM_SS.matcher(raw.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "retryDelay must use the MM:SS format (00-99 minutes, 00-59 seconds), got: "
                            + raw);
        }
        int minutes = Integer.parseInt(matcher.group(1));
        int seconds = Integer.parseInt(matcher.group(2));
        if (seconds > MAX_SECONDS) {
            throw new IllegalArgumentException(
                    "retryDelay seconds must be 00-59, got: " + raw);
        }
        if (minutes > MAX_MINUTES) {
            throw new IllegalArgumentException(
                    "retryDelay minutes must be 00-99, got: " + raw);
        }
        Duration parsed = Duration.ofMinutes(minutes).plusSeconds(seconds);
        if (parsed.compareTo(MIN) < 0) {
            throw new IllegalArgumentException(
                    "retryDelay must be at least 00:01, got: " + raw);
        }
        return new RetryDelay(parsed);
    }

    /** Builds a delay from a whole number of seconds, validated the same way. */
    public static RetryDelay ofSeconds(long seconds) {
        if (seconds < 0 || seconds > MAX.toSeconds()) {
            throw new IllegalArgumentException(
                    "retryDelay out of range (0.." + MAX.toSeconds() + " seconds): " + seconds);
        }
        return new RetryDelay(Duration.ofSeconds(seconds));
    }

    /** The canonical {@code MM:SS} form; the round-trip target of {@link #parse}. */
    @Override
    public String toString() {
        long totalSeconds = value.getSeconds();
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    /** The instant this delay elapses, measured from the attempt's failure. */
    public java.time.Instant applyTo(java.time.Instant failedAt) {
        return failedAt.plus(value);
    }
}
