package com.shivang.obd.contact.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PUT semantics: replaces the mutable group representation. */
public record UpdateContactGroupRequest(
    @NotBlank @Size(max = 150) String name,
    @Size(max = 5000) String description
) {
}
