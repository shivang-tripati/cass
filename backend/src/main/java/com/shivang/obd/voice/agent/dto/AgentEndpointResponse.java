package com.shivang.obd.voice.agent.dto;

import com.shivang.obd.voice.call.EndpointType;
import java.time.Instant;
import java.util.UUID;

/**
 * Agent endpoint response (VB-4A).
 *
 * @param id           endpoint id
 * @param agentId      owning agent
 * @param tenantId     owning tenant
 * @param endpointType destination technology
 * @param dialTarget   dial string FreeSWITCH resolves
 * @param enabled      enabled endpoints make the agent reachable
 * @param createdAt    creation timestamp
 * @param updatedAt    last update timestamp
 */
public record AgentEndpointResponse(
    UUID id,
    UUID agentId,
    UUID tenantId,
    EndpointType endpointType,
    String dialTarget,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {
}
