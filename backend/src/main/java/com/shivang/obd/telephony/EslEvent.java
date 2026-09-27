package com.shivang.obd.telephony;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a FreeSWITCH ESL event.
 * <p>
 * Contains the event name and headers.
 */
public class EslEvent {

    private final String eventName;
    private final Map<String, String> headers = new HashMap<>();

    public EslEvent(String eventName) {
        this.eventName = eventName;
    }

    public String getEventName() {
        return eventName;
    }

    public void addHeader(String key, String value) {
        headers.put(key, value);
    }

    public String getHeader(String key) {
        return headers.get(key);
    }

    public Map<String, String> getHeaders() {
        return Map.copyOf(headers);
    }

    /**
     * Gets the Call-UUID header (FreeSWITCH channel UUID).
     */
    public String getCallUuid() {
        return headers.get("Call-UUID");
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
     * a caller↔agent bridge; the event's own Call-UUID is the bridge anchor
     * (the caller channel).
     */
    public String getBridgeBUuid() {
        return headers.get("Bridge-B-Unique-ID");
    }
}