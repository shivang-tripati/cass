package com.shivang.obd.voice.call;

/**
 * Call purpose/type for the voice core.
 * <p>
 * This is distinct from CampaignType. CampaignType is a product orchestration concept.
 * CallType is a universal voice classification.
 */
public enum CallType {
    /** Voice Blast / OBD outbound call. */
    VOICE_BLAST,

    /** Contact Center inbound call. */
    CONTACT_CENTER_INBOUND,

    /** Contact Center outbound call (preview/progressive/predictive). */
    CONTACT_CENTER_OUTBOUND,

    /** AI-driven voice call. */
    AI,

    /** Internal/test call. */
    INTERNAL
}