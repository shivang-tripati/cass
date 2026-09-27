package com.shivang.obd.voice.media;

/**
 * Response from an outbound dial attempt.
 */
public record OutboundDialResponse(
    OutboundDialResult result,
    String failureReason,
    String providerCallId
) {
    public static OutboundDialResponse accepted(String providerCallId) {
        return new OutboundDialResponse(OutboundDialResult.DIAL_REQUEST_ACCEPTED, null, providerCallId);
    }

    public static OutboundDialResponse busy() {
        return new OutboundDialResponse(OutboundDialResult.BUSY, "Destination busy", null);
    }

    public static OutboundDialResponse noAnswer() {
        return new OutboundDialResponse(OutboundDialResult.NO_ANSWER, "No answer", null);
    }

    public static OutboundDialResponse rejected(String reason) {
        return new OutboundDialResponse(OutboundDialResult.REJECTED, reason, null);
    }

    public static OutboundDialResponse providerUnavailable() {
        return new OutboundDialResponse(OutboundDialResult.PROVIDER_UNAVAILABLE, "Provider unavailable", null);
    }

    public static OutboundDialResponse failed(String reason) {
        return new OutboundDialResponse(OutboundDialResult.FAILED, reason, null);
    }
}