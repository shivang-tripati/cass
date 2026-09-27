package com.shivang.obd.voice.endpoint;

import com.shivang.obd.voice.call.EndpointType;
import java.util.UUID;

/**
 * Agent endpoint abstraction for CONNECT_BY_AGENT calls.
 * <p>
 * Describes the destination for an agent leg without exposing
 * FreeSWITCH implementation details.
 * <p>
 * Phase R: Placeholder interface. Actual endpoint implementations
 * (WebRTC, Mobile SIP, External Forward) will be added in later phases.
 */
public interface AgentEndpoint {

    /**
     * Endpoint type.
     */
    EndpointType getType();

    /**
     * Target identifier (SIP URI, phone number, agent ID, etc.).
     */
    String getTarget();

    /**
     * Optional agent ID if this endpoint maps to a specific agent.
     */
    UUID getAgentId();

    /**
     * Optional queue ID if this endpoint is a queue.
     */
    UUID getQueueId();
}