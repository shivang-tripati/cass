package com.shivang.obd.campaign.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;

/**
 * Request to create a call attempt.
 */
public record CreateCallAttemptRequest(
    @NotNull UUID contactId,
    @NotNull UUID didId,
    @NotNull @Positive Integer attemptNumber,
    Instant scheduledAt
) {
}