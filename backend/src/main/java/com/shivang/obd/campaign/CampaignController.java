package com.shivang.obd.campaign;

import com.shivang.obd.campaign.dto.CampaignExecutionResponse;
import com.shivang.obd.campaign.dto.CampaignReadinessResponse;
import com.shivang.obd.campaign.dto.CampaignResponse;
import com.shivang.obd.campaign.dto.CallAttemptResponse;
import com.shivang.obd.campaign.dto.CreateCallAttemptRequest;
import com.shivang.obd.campaign.dto.CreateCampaignRequest;
import com.shivang.obd.campaign.dto.ExecuteCampaignRequest;
import com.shivang.obd.campaign.dto.UpdateCampaignRequest;
import com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.api.response.PaginationMetadata;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
@Tag(name = "Campaigns", description = "Tenant-owned outbound campaign configuration. "
    + "Listing and reads are implicitly scoped to the caller's organizational boundary "
    + "(own tenant / own reseller hierarchy / platform).")
public class CampaignController {

    private final CampaignService campaignService;
    private final CampaignReadinessService readinessService;
    private final CampaignExecutionService executionService;
    private final CallAttemptService callAttemptService;

    @Operation(
        summary = "Create a campaign",
        description = "Creates a DRAFT campaign in the caller's context tenant. Platform and "
            + "reseller callers may target any authorized tenant via the tenantId parameter; "
            + "tenant callers may not override their own boundary. Configuration must satisfy "
            + "the rules of the given campaignType (content selection, type-specific payload).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Campaign created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation or business rule failed / tenant unknown or inactive")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_MANAGE capability for the target tenant")
    @PostMapping
    public ResponseEntity<ApiResponse<CampaignResponse>> create(
        @Valid @RequestBody CreateCampaignRequest request,
        @RequestParam(required = false) UUID tenantId
    ) {
        ApiResponse<CampaignResponse> body = campaignService.create(request, tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "Get a campaign by id",
        description = "Scoped lookups constrain the query by the caller's tenant boundary, so "
            + "a foreign campaign and a nonexistent campaign are indistinguishable (404). "
            + "Platform callers can read any non-deleted campaign.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_VIEW capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @GetMapping("/{id}")
    public ApiResponse<CampaignResponse> getById(@PathVariable UUID id) {
        return campaignService.getById(id);
    }

    @Operation(
        summary = "List campaigns",
        description = "Paginated, sortable and searchable listing scoped to the caller's "
            + "organizational context (own tenant / active tenants of the reseller hierarchy / "
            + "platform-wide). Soft-deleted campaigns are always excluded.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid status/campaignType/runMode filter")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_VIEW capability")
    @GetMapping
    public ApiResponse<List<CampaignResponse>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt,desc") String[] sort,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String campaignType,
        @RequestParam(required = false) String runMode,
        @RequestParam(required = false) String search
    ) {
        return campaignService.list(page, size, sort, status, campaignType, runMode, search);
    }

    @Operation(
        summary = "Update a campaign",
        description = "Replaces the mutable configuration (PUT semantics: omitted optional blocks "
            + "are cleared). campaignType is immutable. The result must remain valid for the "
            + "campaign's type.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation or business rule failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @PutMapping("/{id}")
    public ApiResponse<CampaignResponse> update(
        @PathVariable UUID id, @Valid @RequestBody UpdateCampaignRequest request
    ) {
        return campaignService.update(id, request);
    }

    @Operation(
        summary = "Delete a campaign",
        description = "Soft delete: the row is preserved with deletion audit columns stamped and "
            + "disappears from all normal queries.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        campaignService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Transition campaign lifecycle state",
        description = "Applies an explicit lifecycle transition (e.g. DRAFT -> SCHEDULED, "
            + "SCHEDULED -> PAUSED, PAUSED -> RUNNING, any active state -> ARCHIVED). Illegal "
            + "transitions and engine-driven transitions (SCHEDULED -> RUNNING, RUNNING -> "
            + "COMPLETED/FAILED) are rejected with 409. Activating a DRAFT campaign requires "
            + "complete, coherent configuration including a timezone-valid schedule. Requires "
            + "the CAMPAIGN_EXECUTE capability on the owning tenant.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status changed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Unknown status value / activation validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_EXECUTE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Illegal or engine-driven lifecycle transition")
    @PatchMapping("/{id}/status")
    public ApiResponse<CampaignResponse> changeStatus(
        @PathVariable UUID id, @Valid @RequestBody UpdateCampaignStatusRequest request
    ) {
        return campaignService.changeStatus(id, request);
    }

    @Operation(
        summary = "Clone a campaign",
        description = "Creates a fresh DRAFT lineage successor of the source campaign: same "
            + "tenant and configuration, version incremented by 1, clonedFromCampaignId set to "
            + "the source id, new identity and audit trail. The source is resolved within the "
            + "caller's boundary, so cloning foreign campaigns is indistinguishable from a "
            + "missing campaign (404). Requires the CAMPAIGN_MANAGE capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Clone created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Source not found or outside caller boundary")
    @PostMapping("/{id}/clone")
    public ResponseEntity<ApiResponse<CampaignResponse>> clone(@PathVariable UUID id) {
        ApiResponse<CampaignResponse> body = campaignService.clone(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "Check campaign execution readiness",
        description = "Evaluates whether a campaign is currently ready for execution by checking "
            + "lifecycle state, schedule eligibility, content configuration, and all external "
            + "reference availability (Contact Group, DID, Audio, TTS). Scoped to the caller's "
            + "tenant boundary — foreign campaigns are indistinguishable from nonexistent (404). "
            + "Requires CAMPAIGN_VIEW capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Readiness evaluation result")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_VIEW capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @GetMapping("/{id}/readiness")
    public ApiResponse<CampaignReadinessResponse> getReadiness(@PathVariable UUID id) {
        return ResponseFactory.ok(readinessService.evaluate(id));
    }

    @Operation(
        summary = "Create a campaign execution",
        description = "Requests execution of a campaign. Validates readiness first; rejects if not ready. "
            + "Supports optional idempotency key to prevent duplicate executions. "
            + "Returns execution identity with REQUESTED status. Actual execution is performed by "
            + "the future execution engine. Requires CAMPAIGN_EXECUTE capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Execution requested")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Campaign not ready / invalid request")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_EXECUTE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Duplicate idempotency key for different campaign")
    @PostMapping("/{id}/executions")
    public ResponseEntity<ApiResponse<CampaignExecutionResponse>> execute(
            @PathVariable UUID id, @Valid @RequestBody ExecuteCampaignRequest request) {
        ApiResponse<CampaignExecutionResponse> body = executionService.execute(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "Get a campaign execution by id",
        description = "Scoped lookup constrained by the caller's tenant boundary. "
            + "Foreign executions are indistinguishable from nonexistent (404). "
            + "Requires CAMPAIGN_EXECUTE capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_EXECUTE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @GetMapping("/{campaignId}/executions/{executionId}")
    public ApiResponse<CampaignExecutionResponse> getExecution(
            @PathVariable UUID campaignId, @PathVariable UUID executionId) {
        return executionService.getExecution(executionId);
    }

    @Operation(
        summary = "List executions for a campaign",
        description = "Lists all execution attempts for a campaign, ordered by requested time (newest first). "
            + "Scoped to the caller's tenant boundary. Requires CAMPAIGN_EXECUTE capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_EXECUTE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @GetMapping("/{campaignId}/executions")
    public ApiResponse<java.util.List<CampaignExecutionResponse>> listExecutions(@PathVariable UUID campaignId) {
        return executionService.listExecutions(campaignId);
    }

    @Operation(
        summary = "Create a call attempt",
        description = "Queues a call attempt for a specific contact within a campaign execution. "
            + "Validates execution/campaign/contact/DID ownership and availability. "
            + "Returns QUEUED attempt. Actual dialing is performed by the future execution engine. "
            + "Requires CAMPAIGN_EXECUTE capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Call attempt queued")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed / invalid ownership")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_EXECUTE capability")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Duplicate attempt for same execution/contact/attemptNumber")
    @PostMapping("/{campaignId}/executions/{executionId}/attempts")
    public ResponseEntity<ApiResponse<CallAttemptResponse>> createAttempt(
            @PathVariable UUID campaignId,
            @PathVariable UUID executionId,
            @Valid @RequestBody CreateCallAttemptRequest request) {
        ApiResponse<CallAttemptResponse> body = callAttemptService.createAttempt(campaignId, executionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "Get a call attempt by id",
        description = "Scoped lookup constrained by the caller's tenant boundary. "
            + "Foreign attempts are indistinguishable from nonexistent (404). "
            + "Requires CAMPAIGN_EXECUTE capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_EXECUTE capability")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @GetMapping("/{campaignId}/executions/{executionId}/attempts/{attemptId}")
    public ApiResponse<CallAttemptResponse> getAttempt(
            @PathVariable UUID campaignId,
            @PathVariable UUID executionId,
            @PathVariable UUID attemptId) {
        return callAttemptService.getAttempt(campaignId, executionId, attemptId);
    }

    @Operation(
        summary = "List call attempts for an execution",
        description = "Lists all call attempts for an execution, ordered by scheduled time (oldest first). "
            + "Scoped to the caller's tenant boundary. Requires CAMPAIGN_EXECUTE capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_EXECUTE capability")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @GetMapping("/{campaignId}/executions/{executionId}/attempts")
    public ApiResponse<java.util.List<CallAttemptResponse>> listAttempts(
            @PathVariable UUID campaignId,
            @PathVariable UUID executionId) {
        return callAttemptService.listAttempts(campaignId, executionId);
    }

    @Operation(
        summary = "Mark a call attempt as IN_PROGRESS",
        description = "Transitions attempt from QUEUED to IN_PROGRESS. "
            + "Future execution engine calls this when starting dialing. "
            + "Requires CAMPAIGN_EXECUTE capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid ownership")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_EXECUTE capability")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Illegal transition (attempt not QUEUED)")
    @PatchMapping("/{campaignId}/executions/{executionId}/attempts/{attemptId}/in-progress")
    public ApiResponse<CallAttemptResponse> markInProgress(
            @PathVariable UUID campaignId,
            @PathVariable UUID executionId,
            @PathVariable UUID attemptId) {
        return callAttemptService.markInProgress(campaignId, executionId, attemptId);
    }

    @Operation(
        summary = "Mark a call attempt as COMPLETED",
        description = "Transitions attempt from IN_PROGRESS to COMPLETED. "
            + "Future execution engine calls this when call finishes successfully. "
            + "Requires CAMPAIGN_EXECUTE capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid ownership")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_EXECUTE capability")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Illegal transition (attempt not IN_PROGRESS)")
    @PatchMapping("/{campaignId}/executions/{executionId}/attempts/{attemptId}/completed")
    public ApiResponse<CallAttemptResponse> markCompleted(
            @PathVariable UUID campaignId,
            @PathVariable UUID executionId,
            @PathVariable UUID attemptId) {
        return callAttemptService.markCompleted(campaignId, executionId, attemptId);
    }

    @Operation(
        summary = "Mark a call attempt as FAILED",
        description = "Transitions attempt from IN_PROGRESS or QUEUED to FAILED. "
            + "Future execution engine calls this when call fails. "
            + "Provide failureCode and failureReason for diagnostics. "
            + "Requires CAMPAIGN_EXECUTE capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid ownership")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_EXECUTE capability")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Illegal transition")
    @PatchMapping("/{campaignId}/executions/{executionId}/attempts/{attemptId}/failed")
    public ApiResponse<CallAttemptResponse> markFailed(
            @PathVariable UUID campaignId,
            @PathVariable UUID executionId,
            @PathVariable UUID attemptId,
            @RequestParam(required = false) String failureCode,
            @RequestParam(required = false) String failureReason) {
        return callAttemptService.markFailed(campaignId, executionId, attemptId, failureCode, failureReason);
    }

    @Operation(
        summary = "Cancel a call attempt",
        description = "Transitions attempt from QUEUED or IN_PROGRESS to CANCELLED. "
            + "Future execution engine calls this to abandon an attempt. "
            + "Requires CAMPAIGN_EXECUTE capability.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid ownership")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing CAMPAIGN_EXECUTE capability")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Illegal transition")
    @PatchMapping("/{campaignId}/executions/{executionId}/attempts/{attemptId}/cancel")
    public ApiResponse<CallAttemptResponse> cancel(
            @PathVariable UUID campaignId,
            @PathVariable UUID executionId,
            @PathVariable UUID attemptId) {
        return callAttemptService.cancel(campaignId, executionId, attemptId);
    }
}
