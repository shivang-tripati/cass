package com.shivang.obd.audio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PUT semantics: replaces the mutable asset metadata. */
public record UpdateAudioAssetRequest(
    @NotBlank @Size(max = 150) String name,
    @Size(max = 5000) String description
) {
}
