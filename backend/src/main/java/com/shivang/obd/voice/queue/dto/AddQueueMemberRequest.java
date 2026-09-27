package com.shivang.obd.voice.queue.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Add-member request (VB-4B). The agent must belong to the same tenant
 * as the queue. Adding is idempotent: an existing INACTIVE membership is
 * reactivated, an existing ACTIVE membership is returned unchanged.
 */
public record AddQueueMemberRequest(
    @NotNull UUID agentId
) {
}
