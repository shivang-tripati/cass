package com.shivang.obd.voice.media;

import java.util.UUID;

/**
 * Request to initiate an outbound dial.
 * <p>
 * Contains only the information genuinely required to place a call.
 * Gateway routing is internal and never exposed via API.
 */
public record OutboundDialRequest(
    UUID callAttemptId,
    String callerId,           // DID E.164 number
    String destinationNumber,  // Contact E.164 number
    UUID executionId,
    int attemptNumber,
    GatewayRoute routing        // internal, nullable for backward compat
) {
    public OutboundDialRequest(UUID callAttemptId, String callerId, String destinationNumber,
                               UUID executionId, int attemptNumber) {
        this(callAttemptId, callerId, destinationNumber, executionId, attemptNumber, null);
    }
}