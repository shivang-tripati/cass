package com.shivang.obd.tts;

/**
 * Approval lifecycle of a TTS template. Platform-created templates may
 * start APPROVED (system/prebuilt); tenant-created templates start
 * PENDING_APPROVAL and must be approved before campaign use.
 */
public enum TtsTemplateStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
}
