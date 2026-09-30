package com.shivang.obd.telephony;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Represents a FreeSWITCH ESL event.
 * <p>
 * Contains the event name and headers.
 *
 * <h2>Channel identity (Phase D, J1)</h2>
 *
 * <p>FreeSWITCH does <strong>not</strong> emit a {@code Call-UUID} header. This
 * was measured against the live switch, not read from a specification: five event
 * types ({@code CHANNEL_CREATE}, {@code CHANNEL_ANSWER}, {@code PLAYBACK_START},
 * {@code PLAYBACK_STOP}, {@code CHANNEL_HANGUP}) were captured with complete,
 * unfiltered header dumps, and {@code Call-UUID} was absent from every one.
 *
 * <p>The channel UUID is present, under five different names:
 *
 * <pre>
 * Unique-ID          = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
 * Channel-Call-UUID  = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
 * Caller-Unique-ID   = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
 * variable_call_uuid = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
 * variable_uuid      = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
 * Call-UUID          = ABSENT
 * </pre>
 *
 * <p>Reading {@code Call-UUID} therefore returned {@code null} for every event,
 * so no event correlated to a {@code CallAttempt} and the correlation warning
 * fired on every event. {@link #getCallUuid()} now resolves the identity through
 * {@link #CHANNEL_IDENTITY_HEADERS}.
 *
 * <p>Header lookup is case-insensitive here, matching {@link EslMessage#header},
 * because FreeSWITCH's casing is not stable across the event set and a
 * case-sensitive miss would silently reintroduce the same class of defect.
 */
public class EslEvent {

    /**
     * Headers that carry the channel UUID, in resolution order.
     *
     * <p>{@code Channel-Call-UUID} is the explicit channel-identity header and is
     * preferred. {@code Unique-ID} is the conventional channel header and is the
     * fallback. {@code Call-UUID} is retained last purely for compatibility with
     * event sources that do emit it; it is not something this FreeSWITCH
     * produces, and it is never the only thing a test should rely on.
     */
    public static final List<String> CHANNEL_IDENTITY_HEADERS = List.of(
            "Channel-Call-UUID",
            "Unique-ID",
            "Call-UUID");

    private final String eventName;

    /**
     * Case-insensitive header storage. {@link TreeMap} with
     * {@link String#CASE_INSENSITIVE_ORDER} makes lookup casing-agnostic while
     * keeping the original-cased key for {@link #getHeaders()} output.
     */
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public EslEvent(String eventName) {
        this.eventName = eventName;
    }

    public String getEventName() {
        return eventName;
    }

    public void addHeader(String key, String value) {
        headers.put(key, value);
    }

    /** Case-insensitive header lookup; {@code null} when absent. */
    public String getHeader(String key) {
        return key == null ? null : headers.get(key);
    }

    public Map<String, String> getHeaders() {
        return Map.copyOf(new LinkedHashMap<>(headers));
    }

    /**
     * The FreeSWITCH channel UUID for this event.
     *
     * <p>Resolved through {@link #CHANNEL_IDENTITY_HEADERS}. Returns {@code null}
     * only when the event genuinely carries no channel identity, which callers
     * must treat as "cannot correlate" rather than "unknown call".
     *
     * @see #CHANNEL_IDENTITY_HEADERS
     */
    public String getCallUuid() {
        for (String header : CHANNEL_IDENTITY_HEADERS) {
            String value = headers.get(header);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * The channel UUID as supplied by the application when it originated the
     * call, when FreeSWITCH reports one.
     *
     * <p>This is the identity the platform pins via {@code origination_uuid}. It
     * is the same value as {@link #getCallUuid()} for the channel that was
     * originated, and is exposed separately because it is what makes the
     * correlation deterministic from the first event.
     */
    public String getOriginationUuid() {
        return headers.get("variable_origination_uuid");
    }

    /**
     * Gets the Hangup-Cause header for CHANNEL_HANGUP events.
     */
    public String getHangupCause() {
        return headers.get("Hangup-Cause");
    }

    /**
     * Gets the Answer-State header for CHANNEL_ANSWER events.
     */
    public String getAnswerState() {
        return headers.get("Answer-State");
    }

    /**
     * Gets the reported DTMF digit for CHANNEL_DTMF events
     * (FreeSWITCH {@code DTMF-Digit} header). One character: 0-9, *, or #.
     */
    public String getDtmfDigit() {
        return headers.get("DTMF-Digit");
    }

    /**
     * Gets the other channel UUID for CHANNEL_BRIDGE events (FreeSWITCH
     * {@code Bridge-B-Unique-ID} header). Present on the event that confirms
     * a caller/agent bridge; the event's own channel UUID is the bridge anchor
     * (the caller channel).
     */
    public String getBridgeBUuid() {
        return headers.get("Bridge-B-Unique-ID");
    }

    /** True when this event reports the channel UUID under any known header. */
    public boolean hasChannelIdentity() {
        return getCallUuid() != null;
    }

    /** The event name normalised for logging and dispatch diagnostics. */
    public String getEventNameUpper() {
        return eventName == null ? "" : eventName.toUpperCase(Locale.ROOT);
    }
}
