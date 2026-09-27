package com.shivang.obd.audio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Audio asset registration. Metadata-only in this phase: the binary
 * transfer pipeline is deferred; {@code storageReference} records the
 * intended logical location for the future object-store integration.
 */
public record CreateAudioAssetRequest(
    @NotBlank @Size(max = 150) String name,
    @Size(max = 5000) String description,

    @NotBlank @Size(max = 255)
    @Pattern(regexp = "^[^/\\\\]+$", message = "File name must not contain path separators.")
    String fileName,

    @NotBlank @Size(max = 100) String contentType,

    @NotNull @Positive Long fileSize,

    /** Optional; media-derived duration is deferred. */
    @Positive Integer durationSeconds,

    /** Optional SHA-256 hex of the payload when known. */
    @Pattern(regexp = "^[a-fA-F0-9]{64}$", message = "Checksum must be a 64-character SHA-256 hex string.")
    String checksum,

    /** Optional logical storage location for the future object store. */
    @Size(max = 500) String storageReference
) {
}
