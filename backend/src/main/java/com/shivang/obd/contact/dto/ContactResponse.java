package com.shivang.obd.contact.dto;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record ContactResponse(
    UUID id,
    UUID tenantId,
    String firstName,
    String lastName,
    String phoneNumber,
    String email,
    JsonNode attributes,
    Instant createdAt,
    Instant updatedAt
) {
}
