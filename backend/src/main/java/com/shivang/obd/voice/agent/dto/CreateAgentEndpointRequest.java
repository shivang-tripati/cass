package com.shivang.obd.voice.agent.dto;

import com.shivang.obd.voice.call.EndpointType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create-endpoint request (VB-4A).
 *
 * <p>VB-4A supports the endpoint types the existing FreeSWITCH setup can
 * dial (VB-3 set): SIP and EXTERNAL_FORWARD. WEBRTC/MOBILE_APP/AI remain
 * modeled but out of scope.</p>
 *
 * @param agentId    owning agent
 * @param endpointType destination technology
 * @param dialTarget dial string FreeSWITCH can resolve
 *                   (SIP: {@code user@host[:port]}; EXTERNAL_FORWARD: E.164)
 */
public record CreateAgentEndpointRequest(
    @NotNull UUID agentId,
    @NotNull EndpointType endpointType,
    @NotBlank @Size(max = 255) String dialTarget
) {
}
