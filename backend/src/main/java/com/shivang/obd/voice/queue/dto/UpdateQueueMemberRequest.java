package com.shivang.obd.voice.queue.dto;

import com.shivang.obd.voice.queue.QueueMemberStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Membership status update (VB-4B). Affects only the queue↔agent
 * relationship — never the agent's own administrative status, presence,
 * concurrency budget or endpoints.
 */
public record UpdateQueueMemberRequest(
    @NotNull QueueMemberStatus status
) {
}
