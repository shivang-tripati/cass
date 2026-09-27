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

    /**
     * Absolute path, as seen <em>by FreeSWITCH</em>, at which
     * {@link #getBaseDirectory()} is mounted (VB-6E).
     * <p>
     * The application writes audio under {@code baseDirectory}; FreeSWITCH
     * must be handed a path it can actually open. Those two are the same
     * directory in a single-host deployment but are <b>not</b> the same string
     * when the application and FreeSWITCH run in different containers, so the
     * mapping is explicit configuration rather than an assumption. The default
     * is the standard FreeSWITCH sounds directory, which makes an unpackaged
     * single-host deployment work with no extra configuration.
     * <p>
     * Consumed only by {@link MediaUriResolver}, which is the single place a
     * logical storage reference becomes a media path.
     */
    @NotBlank
    private String freeswitchMediaRoot = "/usr/share/freeswitch/sounds";

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

    public String getFreeswitchMediaRoot() {
        return freeswitchMediaRoot;
    }

    public void setFreeswitchMediaRoot(String freeswitchMediaRoot) {
        this.freeswitchMediaRoot = freeswitchMediaRoot;
    }
}
