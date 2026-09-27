package com.shivang.obd.voice.dtmf;

import tools.jackson.databind.JsonNode;
import java.util.Optional;
import org.springframework.lang.Nullable;

/**
 * DTMF interaction configuration (VB-2), parsed from the campaign's
 * {@code type_config} JSONB payload — the configuration surface that
 * {@code CampaignType.DTMF} already requires (V14 / readiness checks).
 * <p>
 * Recognized keys under {@code "dtmf"}:
 * <pre>
 * {
 *   "dtmf": {
 *     "expected":     "1",      // required: digit or digit sequence, 0-9 and * / # only
 *     "maxDigits":    8,        // optional: collection cap, >= expected length (default: expected length)
 *     "terminator":   "#",      // optional: end-of-input key (0-9,*,#); default none
 *     "timeoutSecs":  10,       // optional: 1..120 (default 10)
 *     "action":       "CONNECT_BY_AGENT"  // optional (VB-3): TERMINATE (default) | CONNECT_BY_AGENT
 *   }
 * }
 * </pre>
 * Parsing is strict and total: any missing/extra-typed/unrecognized-shaped
 * value throws {@link DtmfConfigInvalidException} — invalid configuration
 * must fail the interaction as {@code DTMF_CONFIG_INVALID}, never silently
 * degrade. No IVR framework: one expected sequence, one timeout, explicit
 * invalid-input behavior (terminal INVALID, no retry).
 */
public record DtmfConfig(
        String expected,
        int maxDigits,
        @Nullable String terminator,
        int timeoutSecs,
        String action) {

    /** Default collection window when the configuration omits it. */
    public static final int DEFAULT_TIMEOUT_SECS = 10;

    /** Default post-result action when the configuration omits it. */
    public static final String DEFAULT_ACTION = DtmfActions.TERMINATE;

    /** Hard bounds — protects against absurd payloads and poller starvation. */
    static final int MAX_SEQUENCE_LENGTH = 16;
    static final int MIN_TIMEOUT_SECS = 1;
    static final int MAX_TIMEOUT_SECS = 120;

    /**
     * Parses and validates the {@code "dtmf"} node of a campaign type config.
     *
     * @throws DtmfConfigInvalidException if the payload is absent, malformed
     *                                    or fails any validation rule
     */
    public static DtmfConfig fromTypeConfig(JsonNode typeConfig) {
        if (typeConfig == null || !typeConfig.isObject()) {
            throw new DtmfConfigInvalidException("type_config is missing or not an object");
        }
        JsonNode node = typeConfig.get("dtmf");
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new DtmfConfigInvalidException("type_config.dtmf is missing or not a non-empty object");
        }

        JsonNode expectedNode = node.get("expected");
        if (expectedNode == null || !expectedNode.isTextual() || expectedNode.asText().isBlank()) {
            throw new DtmfConfigInvalidException("dtmf.expected is required (digit sequence)");
        }
        String expected = expectedNode.asText().trim();

        if (expected.length() > MAX_SEQUENCE_LENGTH) {
            throw new DtmfConfigInvalidException("dtmf.expected exceeds " + MAX_SEQUENCE_LENGTH + " digits");
        }
        for (int i = 0; i < expected.length(); i++) {
            if (!isCollectableDigit(expected.charAt(i))) {
                throw new DtmfConfigInvalidException(
                        "dtmf.expected contains unsupported character '" + expected.charAt(i) + "'");
            }
        }

        JsonNode maxDigitsNode = node.get("maxDigits");
        int maxDigits = expected.length();
        if (maxDigitsNode != null && !maxDigitsNode.isNull()) {
            if (!maxDigitsNode.isIntegralNumber()) {
                throw new DtmfConfigInvalidException("dtmf.maxDigits must be an integer");
            }
            maxDigits = maxDigitsNode.asInt();
            if (maxDigits < expected.length() || maxDigits > MAX_SEQUENCE_LENGTH) {
                throw new DtmfConfigInvalidException(
                        "dtmf.maxDigits must be between expected length (" + expected.length()
                                + ") and " + MAX_SEQUENCE_LENGTH);
            }
        }

        String terminator = null;
        JsonNode terminatorNode = node.get("terminator");
        if (terminatorNode != null && !terminatorNode.isNull()) {
            if (!terminatorNode.isTextual()) {
                throw new DtmfConfigInvalidException("dtmf.terminator must be a single digit key");
            }
            String value = terminatorNode.asText().trim();
            if (value.length() != 1 || !isCollectableDigit(value.charAt(0))) {
                throw new DtmfConfigInvalidException("dtmf.terminator must be one of 0-9, *, #");
            }
            terminator = value;
        }

        JsonNode timeoutNode = node.get("timeoutSecs");
        int timeoutSecs = DEFAULT_TIMEOUT_SECS;
        if (timeoutNode != null && !timeoutNode.isNull()) {
            if (!timeoutNode.isIntegralNumber()) {
                throw new DtmfConfigInvalidException("dtmf.timeoutSecs must be an integer");
            }
            timeoutSecs = timeoutNode.asInt();
            if (timeoutSecs < MIN_TIMEOUT_SECS || timeoutSecs > MAX_TIMEOUT_SECS) {
                throw new DtmfConfigInvalidException(
                        "dtmf.timeoutSecs must be between " + MIN_TIMEOUT_SECS + " and " + MAX_TIMEOUT_SECS);
            }
        }

        String action = DEFAULT_ACTION;
        JsonNode actionNode = node.get("action");
        if (actionNode != null && !actionNode.isNull()) {
            if (!actionNode.isTextual()) {
                throw new DtmfConfigInvalidException("dtmf.action must be a string");
            }
            String value = actionNode.asText().trim().toUpperCase();
            if (!DtmfActions.TERMINATE.equals(value)
                    && !DtmfActions.CONNECT_BY_AGENT.equals(value)) {
                throw new DtmfConfigInvalidException(
                        "dtmf.action must be " + DtmfActions.TERMINATE + " or "
                                + DtmfActions.CONNECT_BY_AGENT);
            }
            action = value;
        }

        return new DtmfConfig(expected, maxDigits, terminator, timeoutSecs, action);
    }

    /** Digits FreeSWITCH reports on CHANNEL_DTMF that VB-2 accepts. */
    static boolean isCollectableDigit(char c) {
        return (c >= '0' && c <= '9') || c == '*' || c == '#';
    }

    /** Whether the given event digit is acceptable input for this configuration. */
    public boolean accepts(char digit) {
        return isCollectableDigit(digit);
    }

    public Optional<String> getTerminator() {
        return Optional.ofNullable(terminator);
    }

    /**
     * Rebuilds the configuration from a persisted interaction snapshot so
     * digit classification always applies the exact configuration the
     * interaction was created with — even if the campaign type_config
     * changed mid-call.
     */
    public static DtmfConfig of(DtmfInteraction interaction) {
        return new DtmfConfig(
                interaction.getExpectedInput(),
                interaction.getMaxDigits(),
                interaction.getTerminator(),
                interaction.getTimeoutSecs(),
                interaction.getActionType() != null
                        ? interaction.getActionType() : DEFAULT_ACTION);
    }
}
