package com.shivang.obd.voice.agent;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.voice.agent.dto.AgentActiveCallResponse;
import com.shivang.obd.voice.agent.dto.AgentAvailabilityResponse;
import com.shivang.obd.voice.agent.dto.AgentCallHistoryResponse;
import com.shivang.obd.voice.agent.dto.AgentCallResponse;
import com.shivang.obd.voice.agent.dto.CreateAgentCallRequest;
import com.shivang.obd.voice.agent.dto.AgentEndpointResponse;
import com.shivang.obd.voice.agent.dto.AgentResponse;
import com.shivang.obd.voice.agent.dto.CreateAgentEndpointRequest;
import com.shivang.obd.voice.agent.dto.CreateAgentRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentEndpointRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentPresenceRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * VB-4A Agent Foundation REST API. Follows the project conventions:
 * /api/v1 paths, ApiResponse envelope, server-derived organizational
 * scope (a foreign agent and a nonexistent agent are the same 404),
 * capability-based authorization (AGENT_VIEW / AGENT_MANAGE / CALL_VIEW).
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
@Tag(name = "Agents", description = "Tenant-owned agent foundation: lifecycle, "
    + "presence, availability, endpoints, active calls and call history. "
    + "VB-4A only — no queues, ACD, inbound or outbound agent calling.")
public class AgentDirectoryController {

    private final AgentDirectoryService directoryService;
    private final AgentCallQueryService callQueryService;
    private final com.shivang.obd.voice.outbound.AgentOutboundApiService outboundCallService;

    // === agent lifecycle ===

    @Operation(
        summary = "Create an agent",
        description = "Creates an agent owned by the caller's context tenant. "
            + "New agents start administratively ACTIVE and present OFFLINE.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Agent created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing AGENT_MANAGE capability")
    @PostMapping
    public ResponseEntity<ApiResponse<AgentResponse>> createAgent(
        @Valid @RequestBody CreateAgentRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(directoryService.createAgent(request));
    }

    @Operation(
        summary = "Get an agent",
        description = "Scoped lookups make a foreign agent and a nonexistent "
            + "agent indistinguishable (404).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{id}")
    public ApiResponse<AgentResponse> getAgent(@PathVariable UUID id) {
        return directoryService.getAgent(id);
    }

    @Operation(
        summary = "List agents",
        description = "Paginated listing scoped to the caller's organizational "
            + "boundary (tenant / reseller hierarchy / platform).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping
    public ApiResponse<List<AgentResponse>> listAgents(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "displayName,asc") String[] sort,
        @RequestParam(required = false) String search
    ) {
        return directoryService.listAgents(page, size, sort, search);
    }

    @Operation(
        summary = "Update an agent",
        description = "Updates display name, concurrency budget and the optional "
            + "identity link. Lifecycle status has its own endpoint.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PutMapping("/{id}")
    public ApiResponse<AgentResponse> updateAgent(
        @PathVariable UUID id, @Valid @RequestBody UpdateAgentRequest request
    ) {
        return directoryService.updateAgent(id, request);
    }

    @Operation(
        summary = "Update administrative status",
        description = "ACTIVE↔SUSPENDED and →DISABLED. DISABLED is terminal. "
            + "Setting the current status is idempotent.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PutMapping("/{id}/status")
    public ApiResponse<AgentResponse> updateStatus(
        @PathVariable UUID id, @Valid @RequestBody UpdateAgentStatusRequest request
    ) {
        return directoryService.updateStatus(id, request);
    }

    // === presence ===

    @Operation(
        summary = "Update runtime presence",
        description = "Agent-declared presence only: AVAILABLE / OFFLINE. "
            + "BUSY is owned by the call lifecycle and cannot be set manually.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PutMapping("/{id}/presence")
    public ApiResponse<AgentResponse> updatePresence(
        @PathVariable UUID id, @Valid @RequestBody UpdateAgentPresenceRequest request
    ) {
        return directoryService.updatePresence(id, request);
    }

    @Operation(
        summary = "Get current availability",
        description = "Deterministic, explainable availability: administrative "
            + "status + presence + endpoint readiness + concurrency budget.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{id}/availability")
    public ApiResponse<AgentAvailabilityResponse> getAvailability(@PathVariable UUID id) {
        return directoryService.getAvailability(id);
    }

    // === endpoints ===

    @Operation(
        summary = "Create an agent endpoint",
        description = "SIP (user@host) and EXTERNAL_FORWARD (E.164) endpoints are "
            + "supported; other types are rejected.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/{id}/endpoints")
    public ResponseEntity<ApiResponse<AgentEndpointResponse>> createEndpoint(
        @PathVariable UUID id, @Valid @RequestBody CreateAgentEndpointRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(directoryService.createEndpoint(id, request));
    }

    @Operation(summary = "List an agent's endpoints",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{id}/endpoints")
    public ApiResponse<List<AgentEndpointResponse>> listEndpoints(@PathVariable UUID id) {
        return directoryService.listEndpoints(id);
    }

    @Operation(summary = "Get an endpoint",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/endpoints/{endpointId}")
    public ApiResponse<AgentEndpointResponse> getEndpoint(
        @PathVariable UUID endpointId
    ) {
        return directoryService.getEndpoint(endpointId);
    }

    @Operation(summary = "Update an endpoint's dial target",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/endpoints/{endpointId}")
    public ApiResponse<AgentEndpointResponse> updateEndpoint(
        @PathVariable UUID endpointId,
        @Valid @RequestBody UpdateAgentEndpointRequest request
    ) {
        return directoryService.updateEndpoint(endpointId, request);
    }

    @Operation(summary = "Enable an endpoint",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/endpoints/{endpointId}/enable")
    public ApiResponse<AgentEndpointResponse> enableEndpoint(
        @PathVariable UUID endpointId
    ) {
        return directoryService.enableEndpoint(endpointId);
    }

    @Operation(summary = "Disable an endpoint",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/endpoints/{endpointId}/disable")
    public ApiResponse<AgentEndpointResponse> disableEndpoint(
        @PathVariable UUID endpointId
    ) {
        return directoryService.disableEndpoint(endpointId);
    }

    @Operation(summary = "Deactivate (soft-delete) an endpoint",
        security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/endpoints/{endpointId}")
    public ResponseEntity<Void> deactivateEndpoint(
        @PathVariable UUID endpointId
    ) {
        return directoryService.deactivateEndpoint(endpointId);
    }

    // === active calls & history ===

    @Operation(
        summary = "List an agent's active calls",
        description = "Derived from the canonical CallSession/CallLeg model. "
            + "Terminated sessions are excluded.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{id}/calls/active")
    public ApiResponse<List<AgentActiveCallResponse>> getActiveCalls(
        @PathVariable UUID id
    ) {
        return callQueryService.getActiveCalls(id);
    }

    @Operation(
        summary = "List an agent's call history",
        description = "Terminal legs of ended sessions, newest first. Supports "
            + "pagination and an optional initiated-at date range (ISO-8601).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{id}/calls")
    public ApiResponse<List<AgentCallHistoryResponse>> getCallHistory(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to
    ) {
        return callQueryService.getCallHistory(id, page, size, from, to);
    }

    // === agent-originated outbound call (VB-4E) ===

    @Operation(
        summary = "Request an outbound call to an external number",
        description = "Agent-originated outbound calling (VB-4E). Reuses the "
            + "existing routing, gateway capacity, canonical CallSession/CallLeg "
            + "and agent reservation foundations. One call per request — no "
            + "campaign, no dialer scheduler.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Call originated (customer leg dialing)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid destination or agent state")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing AGENT_MANAGE capability")
    @PostMapping("/{id}/calls")
    public ResponseEntity<ApiResponse<AgentCallResponse>> requestOutboundCall(
        @PathVariable UUID id,
        @Valid @RequestBody CreateAgentCallRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(outboundCallService.requestCall(id, request.destination()));
    }
}
