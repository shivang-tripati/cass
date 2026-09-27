package com.shivang.obd.voice.queue;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.voice.queue.dto.AddQueueMemberRequest;
import com.shivang.obd.voice.queue.dto.CreateQueueRequest;
import com.shivang.obd.voice.queue.dto.QueueCapacityResponse;
import com.shivang.obd.voice.queue.dto.QueueMemberResponse;
import com.shivang.obd.voice.queue.dto.QueueResponse;
import com.shivang.obd.voice.queue.dto.QueueWaitingCallResponse;
import com.shivang.obd.voice.queue.dto.UpdateQueueMemberRequest;
import com.shivang.obd.voice.queue.dto.UpdateQueueRequest;
import com.shivang.obd.voice.queue.dto.UpdateQueueStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * VB-4B Queue Foundation REST API. Follows the project conventions:
 * /api/v1 paths, ApiResponse envelope, server-derived organizational
 * scope (a foreign queue and a nonexistent queue are the same 404),
 * capability-based authorization (QUEUE_VIEW / QUEUE_MANAGE — seeded in
 * V37).
 */
@RestController
@RequestMapping("/api/v1/queues")
@RequiredArgsConstructor
@Tag(name = "Queues", description = "Tenant-owned queue foundation: lifecycle, "
    + "configuration (capacity/timeout/overflow — persisted, not executed), "
    + "membership and the waiting-call read model. VB-4B only — no ACD "
    + "selection, no dispatch, no inbound/outbound calling.")
public class QueueDirectoryController {

    private final QueueDirectoryService queueDirectoryService;

    // === queue lifecycle ===

    @Operation(
        summary = "Create a queue",
        description = "Creates a queue owned by the caller's context tenant. "
            + "New queues start ACTIVE. Capacity/timeout/overflow are "
            + "persisted configuration only — VB-4B executes none of them.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Queue created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing QUEUE_MANAGE capability")
    @PostMapping
    public ResponseEntity<ApiResponse<QueueResponse>> createQueue(
        @Valid @RequestBody CreateQueueRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(queueDirectoryService.createQueue(request));
    }

    @Operation(
        summary = "Get a queue",
        description = "Scoped lookups make a foreign queue and a nonexistent "
            + "queue indistinguishable (404).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{queueId}")
    public ApiResponse<QueueResponse> getQueue(@PathVariable UUID queueId) {
        return queueDirectoryService.getQueue(queueId);
    }

    @Operation(
        summary = "List queues",
        description = "Paginated listing scoped to the caller's organizational "
            + "boundary (tenant / reseller hierarchy / platform).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping
    public ApiResponse<List<QueueResponse>> listQueues(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "name,asc") String[] sort,
        @RequestParam(required = false) String search
    ) {
        return queueDirectoryService.listQueues(page, size, sort, search);
    }

    @Operation(
        summary = "Update queue configuration",
        description = "Updates name/description/capacity/timeout/overflow. "
            + "Overflow rules are re-validated: same-tenant target, never "
            + "self. Lifecycle status has its own endpoint.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PutMapping("/{queueId}")
    public ApiResponse<QueueResponse> updateQueue(
        @PathVariable UUID queueId, @Valid @RequestBody UpdateQueueRequest request
    ) {
        return queueDirectoryService.updateQueue(queueId, request);
    }

    @Operation(
        summary = "Update queue status",
        description = "ACTIVE↔INACTIVE and →DISABLED. DISABLED is terminal. "
            + "Setting the current status is idempotent.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PutMapping("/{queueId}/status")
    public ApiResponse<QueueResponse> updateStatus(
        @PathVariable UUID queueId, @Valid @RequestBody UpdateQueueStatusRequest request
    ) {
        return queueDirectoryService.updateStatus(queueId, request);
    }

    @Operation(
        summary = "Get queue capacity",
        description = "Configured capacity vs the live WAITING count — a "
            + "report of current state, not an admission decision.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{queueId}/capacity")
    public ApiResponse<QueueCapacityResponse> getCapacity(
        @PathVariable UUID queueId
    ) {
        return queueDirectoryService.getCapacity(queueId);
    }

    // === membership ===

    @Operation(
        summary = "Add a member to the queue",
        description = "Idempotent: reactivates an INACTIVE membership, "
            + "returns an existing ACTIVE one unchanged. Cross-tenant "
            + "agents fail closed (404). Membership operations never "
            + "mutate the agent itself.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/{queueId}/members")
    public ResponseEntity<ApiResponse<QueueMemberResponse>> addMember(
        @PathVariable UUID queueId, @Valid @RequestBody AddQueueMemberRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(queueDirectoryService.addMember(queueId, request));
    }

    @Operation(
        summary = "List the queue's members",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{queueId}/members")
    public ApiResponse<List<QueueMemberResponse>> listMembers(
        @PathVariable UUID queueId
    ) {
        return queueDirectoryService.listMembers(queueId);
    }

    @Operation(
        summary = "Update a membership's status",
        description = "ACTIVE/INACTIVE. Affects only the queue↔agent "
            + "relationship — never the agent itself.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PutMapping("/{queueId}/members/{agentId}")
    public ApiResponse<QueueMemberResponse> updateMember(
        @PathVariable UUID queueId, @PathVariable UUID agentId,
        @Valid @RequestBody UpdateQueueMemberRequest request
    ) {
        return queueDirectoryService.updateMember(queueId, agentId, request);
    }

    @Operation(
        summary = "Remove a member (soft delete)",
        description = "Removes the membership only; the agent is untouched "
            + "and can be re-added later.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @DeleteMapping("/{queueId}/members/{agentId}")
    public ResponseEntity<Void> removeMember(
        @PathVariable UUID queueId, @PathVariable UUID agentId
    ) {
        return queueDirectoryService.removeMember(queueId, agentId);
    }

    @Operation(
        summary = "List an agent's queue memberships",
        description = "All queues the agent belongs to (any membership status).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/memberships/agents/{agentId}")
    public ApiResponse<List<QueueMemberResponse>> listMembershipsForAgent(
        @PathVariable UUID agentId
    ) {
        return queueDirectoryService.listMembershipsForAgent(agentId);
    }

    // === waiting calls (read model) ===

    @Operation(
        summary = "List the queue's waiting calls",
        description = "Current WAITING rows in deterministic dispatch order "
            + "(enteredAt, id). Queue entry/removal is owned by later "
            + "inbound/ACD flows — VB-4B exposes this read model only.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/{queueId}/waiting-calls")
    public ApiResponse<List<QueueWaitingCallResponse>> listWaitingCalls(
        @PathVariable UUID queueId
    ) {
        return queueDirectoryService.listWaitingCalls(queueId);
    }
}
