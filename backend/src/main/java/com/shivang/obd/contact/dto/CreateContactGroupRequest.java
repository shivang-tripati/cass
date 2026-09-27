package com.shivang.obd.contact.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateContactGroupRequest(
    @NotBlank @Size(max = 150) String name,
    @Size(max = 5000) String description
) {
}
