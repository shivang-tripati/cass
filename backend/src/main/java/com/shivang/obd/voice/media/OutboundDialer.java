package com.shivang.obd.voice.media;

/**
 * Provider-agnostic outbound dialing boundary.
 * <p>
 * The campaign orchestration layer calls this interface to place calls.
 * Concrete implementations (Asterisk, Kamailio, SIP trunk, cloud provider)
 * are provided by later phases and do not leak into the campaign domain.
 */
public interface OutboundDialer {

    /**
     * Places an outbound call.
     *
     * @param request the dial request containing caller ID, destination, and attempt metadata
     * @return the provider-independent result of the dial request
     * @throws OutboundDialException if the provider is fundamentally broken/unavailable
     */
    OutboundDialResponse dial(OutboundDialRequest request);
}