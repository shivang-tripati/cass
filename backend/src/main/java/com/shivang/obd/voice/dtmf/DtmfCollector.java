package com.shivang.obd.voice.dtmf;

import org.springframework.lang.Nullable;

/**
 * Pure DTMF input classifier (VB-2).
 * <p>
 * Applies a {@link DtmfConfig} to the digits collected so far and produces
 * a deterministic intermediate/terminal classification. Stateless — all
 * mutable state (collected digits, terminal result) lives on the persisted
 * {@link DtmfInteraction}, keeping this component trivially testable and
 * the persistence layer authoritative.
 * <p>
 * Rules (spec §7):
 * <ul>
 *   <li>0-9 always accepted; * and # only when the configuration uses them
 *       (in the expected sequence or as terminator) — never silently
 *       normalized</li>
 *   <li>A rejected digit is a terminal INVALID (wrong input cannot become
 *       right by collecting more)</li>
 *   <li>Exact match of the expected sequence → terminal VALID</li>
 *   <li>Terminator key ends collection; input must then match exactly →
 *       VALID, otherwise INVALID</li>
 *   <li>maxDigits reached without a match/terminator → terminal INVALID
 *       (over-input), never a rolling window</li>
 *   <li>Timeout with insufficient input → TIMEOUT (applied by the timeout
 *       path, not here)</li>
 * </ul>
 */
public final class DtmfCollector {

    private DtmfCollector() {
    }

    /** Outcome of feeding one digit to the collector. */
    public record FeedResult(String collected, DtmfResultType result, @Nullable String reason) {
        public boolean terminal() {
            return result != DtmfResultType.COLLECTING;
        }

        public static FeedResult collecting(String collected) {
            return new FeedResult(collected, DtmfResultType.COLLECTING, null);
        }

        public static FeedResult terminal(String collected, DtmfResultType result, String reason) {
            return new FeedResult(collected, result, reason);
        }
    }

    /** Outcome of the terminator/timeout evaluation of the current input. */
    public record EvaluateResult(DtmfResultType result, @Nullable String reason) {
    }

    /**
     * Feeds one DTMF digit to the current collected input.
     *
     * @param config  the interaction configuration
     * @param current digits collected so far ("" when none)
     * @param digit   the reported digit
     * @return the new collected input and the resulting classification
     */
    public static FeedResult feed(DtmfConfig config, String current, char digit) {
        if (!config.accepts(digit)) {
            // Outside the collectable digit set: explicit terminal invalid,
            // never silently normalized away.
            return FeedResult.terminal(current, DtmfResultType.INVALID,
                    "Unsupported DTMF digit '" + digit + "'");
        }
        if (config.getTerminator().map(term -> term.charAt(0) == digit).orElse(false)) {
            EvaluateResult evaluated = evaluate(config, current, "terminator");
            return FeedResult.terminal(current, evaluated.result(), evaluated.reason());
        }
        if (current.length() >= config.maxDigits()) {
            // Over-input: more digits than the collection cap allows.
            return FeedResult.terminal(current, DtmfResultType.INVALID,
                    "Maximum digit count (" + config.maxDigits() + ") exceeded");
        }
        String updated = current + digit;
        if (config.expected().equals(updated)) {
            return FeedResult.terminal(updated, DtmfResultType.VALID,
                    "Expected input received");
        }
        if (!config.expected().startsWith(updated)) {
            // Wrong digit: cannot become valid by collecting more.
            return FeedResult.terminal(updated, DtmfResultType.INVALID,
                    "Input does not match expected sequence");
        }
        return FeedResult.collecting(updated);
    }

    /**
     * Evaluates the current input after a terminator key or timeout: the
     * collected input must match the expected sequence exactly.
     */
    public static EvaluateResult evaluate(DtmfConfig config, String current, String trigger) {
        if (config.expected().equals(current)) {
            return new EvaluateResult(DtmfResultType.VALID, "Expected input received (" + trigger + ")");
        }
        return new EvaluateResult(DtmfResultType.INVALID,
                "Incomplete or mismatched input on " + trigger);
    }
}
