package com.shivang.obd.voice.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Update-endpoint request (VB-4A). Only the dial target is editable;
 * type is immutable (change = create a new endpoint) and enablement has
 * its own lifecycle endpoints.
 *
 * @param dialTarget new dial string FreeSWITCH can resolve
 */
public record UpdateAgentEndpointRequest(
    @NotBlank @Size(max = 255) String dialTarget
) {
}
