package com.shivang.obd.voice.media;

/**
 * Exception indicating the outbound dial provider is fundamentally unavailable.
 * <p>
 * Does not represent individual call failures (BUSY, NO_ANSWER) — those are
 * returned as normal {@link OutboundDialResponse} results.
 * <p>
 * Thrown only when the provider cannot be reached or is misconfigured.
 */
public class OutboundDialException extends RuntimeException {
    public OutboundDialException(String message) {
        super(message);
    }

    public OutboundDialException(String message, Throwable cause) {
        super(message, cause);
    }
}