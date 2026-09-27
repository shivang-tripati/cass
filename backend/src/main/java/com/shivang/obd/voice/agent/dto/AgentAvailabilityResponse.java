package com.shivang.obd.voice.agent.dto;

import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentAvailability;
import java.util.UUID;

/**
 * Explainable current-availability result (VB-4A) — answers
 * "Can this agent receive a contact-center call right now?" with
 * machine-readable reason codes, never an opaque boolean.
 *
 * @param agentId      the agent being evaluated
 * @param tenantId     owning tenant (isolation enforced by the service)
 * @param adminStatus  administrative status snapshot
 * @param presence     runtime presence snapshot
 * @param available    final deterministic availability
 * @param reasonCode   machine-readable explanation
 */
public record AgentAvailabilityResponse(
    UUID agentId,
    UUID tenantId,
    AgentAdminStatus adminStatus,
    AgentAvailability presence,
    boolean available,
    String reasonCode
) {
}
