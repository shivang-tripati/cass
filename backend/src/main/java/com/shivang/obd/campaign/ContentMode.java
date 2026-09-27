package com.shivang.obd.campaign;

/**
 * How campaign content is sourced. AUDIO references an approved
 * pre-recorded asset owned by the future Audio module; TTS references an
 * approved template owned by the future TTS module. Campaign stores only
 * the reference — never binaries or template text.
 */
public enum ContentMode {
    AUDIO,
    TTS
}
