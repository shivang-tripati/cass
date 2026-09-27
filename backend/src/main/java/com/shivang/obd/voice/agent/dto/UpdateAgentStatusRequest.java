package com.shivang.obd.voice.agent.dto;

import com.shivang.obd.voice.agent.AgentAdminStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Administrative lifecycle update request (VB-4A).
 *
 * @param adminStatus target administrative status
 */
public record UpdateAgentStatusRequest(
    @NotNull AgentAdminStatus adminStatus
) {
}
