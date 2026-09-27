package com.shivang.obd.audio;

/**
 * Approval lifecycle of an audio asset. Only APPROVED assets may be
 * referenced by executable campaigns. DRAFT/ARCHIVED states were
 * considered and deliberately deferred until a product need exists.
 */
public enum AudioAssetStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
}
