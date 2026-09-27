package com.shivang.obd.reseller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateResellerRequest(
    @Size(max = 150) String name,
    @Size(max = 150) String displayName,
    @Email @Size(max = 255) String supportEmail,
    @Size(max = 500) String logoUrl,
    @Size(max = 20) String primaryColor
) {
}
