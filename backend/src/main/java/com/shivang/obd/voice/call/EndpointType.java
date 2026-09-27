package com.shivang.obd.voice.call;

/**
 * Endpoint type for a call leg.
 * <p>
 * Describes the destination technology, not the FreeSWITCH implementation.
 * Used for routing decisions without scattering endpoint-specific conditionals.
 */
public enum EndpointType {
    /** SIP endpoint (desk phone, softphone). */
    SIP,

    /** WebRTC browser softphone. */
    WEBRTC,

    /** Mobile SIP application. */
    MOBILE_APP,

    /** External PSTN forwarding. */
    EXTERNAL_FORWARD,

    /** AI agent endpoint. */
    AI
}