package com.shivang.obd.voice.media;

/**
 * Outcome of a dial request.
 * <p>
 * Provider-agnostic — does not expose SIP/Asterisk/Kamailio details.
 */
public enum OutboundDialResult {

    /** Dial request accepted by the provider; call is being placed. */
    DIAL_REQUEST_ACCEPTED,

    /** Destination is busy. */
    BUSY,

    /** No answer within timeout. */
    NO_ANSWER,

    /** Provider rejected the dial request (e.g., invalid number, quota exceeded). */
    REJECTED,

    /** Provider unavailable or communication failure. */
    PROVIDER_UNAVAILABLE,

    /** Generic failure not covered by other codes. */
    FAILED
}