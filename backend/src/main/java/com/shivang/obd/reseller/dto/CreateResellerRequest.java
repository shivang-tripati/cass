package com.shivang.obd.reseller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateResellerRequest(
    @NotBlank @Size(max = 150) String name,
    @NotBlank @Size(max = 100)
    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug must be lowercase letters, digits and hyphens.")
    String slug,
    @Size(max = 150) String displayName,
    @Email @Size(max = 255) String supportEmail,
    @Size(max = 255) String customDomain,
    @Size(max = 500) String logoUrl,
    @Size(max = 20) String primaryColor,
    @Valid AdminAccountInput admin
) {
}
