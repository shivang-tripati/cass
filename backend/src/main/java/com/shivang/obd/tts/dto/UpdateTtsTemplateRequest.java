package com.shivang.obd.tts.dto;

import com.shivang.obd.tts.TtsTemplateVariable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** PUT semantics: replaces the mutable template representation. */
public record UpdateTtsTemplateRequest(
    @NotBlank @Size(max = 150) String name,
    @Size(max = 5000) String description,

    @NotBlank @Size(max = 5000) String templateText,

    @NotNull @Size(max = 50) List<TtsTemplateVariable> variables
) {
}
