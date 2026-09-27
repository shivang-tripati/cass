package com.shivang.obd.did;

/**
 * Capabilities a DID supports. The set is intentionally small and typed;
 * new values (VOICE_INBOUND, SMS, ...) are added as explicit enum
 * constants when a product requirement exists — never as free strings.
 */
public enum DidCapability {
    VOICE_OUTBOUND
}
