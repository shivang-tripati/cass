package com.shivang.obd.audio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Audio upload/storage configuration (VB-5B).
 * <p>
 * Follows the {@code telephony.freeswitch} properties convention. Storage
 * is disabled by default for safety, mirroring the FreeSWITCH adapter:
 * upload endpoints stay inactive until an operator deliberately enables
 * them and points them at a writable directory.
 */
@ConfigurationProperties(prefix = "audio.storage")
@Validated
public class AudioStorageProperties {

    /** Whether the audio storage/upload pipeline is enabled. Defaults to false for safety. */
    private boolean enabled = false;

    /**
     * Base directory for stored audio files. Must point outside the
     * application classpath and any source-controlled directory.
     * Required when enabled.
     */
    @NotBlank
    private String baseDirectory = "data/audio";

    /** Maximum accepted upload size in bytes. */
    @Min(1)
    private long maxFileSizeBytes = 5L * 1024 * 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseDirectory() {
        return baseDirectory;
    }

    public void setBaseDirectory(String baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }
}
