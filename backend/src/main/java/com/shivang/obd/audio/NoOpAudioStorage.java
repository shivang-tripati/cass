package com.shivang.obd.audio;

import java.io.InputStream;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Inactive storage used when {@code audio.storage.enabled=false} (the
 * default), mirroring the telephony {@code NoOpVoiceMediaController}
 * pattern: the application context stays wiring-complete, but every
 * upload is rejected with a clear operational error instead of a bean
 * resolution failure. No file is ever written and nothing is ever
 * deletable through this implementation.
 */
@Component
@ConditionalOnProperty(prefix = "audio.storage", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpAudioStorage implements AudioStorage {

    @Override
    public StoredAudio store(UUID tenantId, UUID audioAssetId, String originalFileName, InputStream content) {
        throw new AudioStorageException(
            "Audio storage is disabled (audio.storage.enabled=false); upload is unavailable.");
    }

    @Override
    public void delete(String storageReference) {
        // Storage disabled: nothing this implementation wrote can exist.
    }
}
