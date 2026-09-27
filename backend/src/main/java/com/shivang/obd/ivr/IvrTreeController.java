package com.shivang.obd.ivr;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.ivr.dto.ChangeIvrTreeStatusRequest;
import com.shivang.obd.ivr.dto.CreateIvrTreeRequest;
import com.shivang.obd.ivr.dto.IvrTreeResponse;
import com.shivang.obd.ivr.dto.UpdateIvrTreeRequest;
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
 * Reusable IVR trees (VB-6F).
 *
 * <p><b>Why the whole tree is one request.</b> A tree is only meaningful as a
 * complete structure: exactly one root, no cycles, every node reachable, every
 * transition pointing inside the same tree. Submitting it in one call is what
 * lets it be validated atomically, so an invalid tree can never be persisted and
 * then activated. Per-node endpoints would deliberately allow that state.
 *
 * <p>Reads are implicitly scoped to the caller's organizational boundary, so a
 * foreign tree and a nonexistent tree are indistinguishable (404) — the
 * platform-wide convention, and the reason a cross-tenant probe cannot even
 * confirm an id exists.
 */
@RestController
@RequestMapping("/api/v1/ivr-trees")
@RequiredArgsConstructor
@Tag(name = "IVR Trees", description = "Reusable, tenant-owned multi-level DTMF flows. A tree is "
    + "authored once and referenced by any number of DTMF campaigns, so an identical menu is not "
    + "duplicated per campaign. A campaign captures an immutable snapshot of the tree when its "
    + "execution is created, so editing a live tree never changes a call already in the old flow; "
    + "the change applies to executions created afterwards. Prompts are governed audio assets "
    + "owned by the caller.")
public class IvrTreeController {

    private final IvrTreeService ivrTreeService;

    @Operation(
        summary = "Create an IVR tree",
        description = "Creates a tree in DRAFT state within the caller's tenant, with its complete "
            + "node set and transitions. The whole structure is validated as one unit: a missing or "
            + "duplicated root, a duplicate digit on a node, a dangling or cross-tree target, a "
            + "cycle, an unreachable node, a menu with no transitions, a terminal node without an "
            + "action, an out-of-range wait or retry, or a digit outside 0-9/*/# is rejected. A "
            + "tree must be activated before it can be referenced by a campaign.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
        description = "Tree created in DRAFT state")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
        description = "Schema validation failed, or the tree is structurally invalid (the response "
            + "message names every problem found)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
        description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
        description = "Missing IVR_MANAGE capability for the caller's tenant")
    @PostMapping
    public ResponseEntity<ApiResponse<IvrTreeResponse>> create(
        @Valid @RequestBody CreateIvrTreeRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ivrTreeService.create(request));
    }

    @Operation(
        summary = "Get an IVR tree",
        description = "Returns the tree with its structure inline, so the menu a caller experiences "
            + "is directly readable from this response. Scoped to the caller's boundary, so a "
            + "foreign tree and a nonexistent tree are both 404.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
        description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
        description = "Missing IVR_VIEW capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
        description = "Not found, or outside the caller's boundary")
    @GetMapping("/{id}")
    public ApiResponse<IvrTreeResponse> getById(@PathVariable UUID id) {
        return ivrTreeService.getById(id);
    }

    @Operation(
        summary = "List IVR trees",
        description = "Returns the caller's trees, newest first, optionally filtered by lifecycle "
            + "state.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
        description = "Unknown status filter")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
        description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
        description = "Missing IVR_VIEW capability")
    @GetMapping
    public ApiResponse<List<IvrTreeResponse>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String status
    ) {
        return ivrTreeService.list(page, size, status);
    }

    @Operation(
        summary = "Replace an IVR tree's contents",
        description = "Replaces the tree's complete node set. Execution-affecting fields — nodes, "
            + "transitions, prompts, timing, retries, terminal actions — are all replaced so the "
            + "tree cannot be left half-updated, and the result is revalidated as a whole. "
            + "Running executions are unaffected: each captured its own immutable snapshot, so a "
            + "caller already in the old flow keeps it and the change applies to executions "
            + "created afterwards. That is why no IVR versioning exists.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
        description = "Tree replaced and revalidated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
        description = "Schema validation failed, the tree is structurally invalid, or the tree is "
            + "archived and therefore not editable")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
        description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
        description = "Missing IVR_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
        description = "Not found, or outside the caller's boundary")
    @PutMapping("/{id}")
    public ApiResponse<IvrTreeResponse> update(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateIvrTreeRequest request
    ) {
        return ivrTreeService.update(id, request);
    }

    @Operation(
        summary = "Change an IVR tree's lifecycle state",
        description = "DRAFT to ACTIVE runs the full structural validator plus prompt-resource "
            + "governance, so a tree whose structure is broken or whose audio assets are not "
            + "APPROVED for this tenant cannot become selectable for execution. ACTIVE to "
            + "ARCHIVED retires the tree without deleting it: existing execution snapshots are "
            + "self-contained, so calls already created keep working and stay auditable. A tree "
            + "cannot return to DRAFT, because existing snapshots must keep the flow they were "
            + "created with.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
        description = "Status changed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
        description = "Illegal transition, or the tree failed validation on activation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
        description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
        description = "Missing IVR_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
        description = "Not found, or outside the caller's boundary")
    @PostMapping("/{id}/status")
    public ApiResponse<IvrTreeResponse> changeStatus(
        @PathVariable UUID id,
        @Valid @RequestBody ChangeIvrTreeStatusRequest request
    ) {
        return ivrTreeService.changeStatus(id, request);
    }

    @Operation(
        summary = "Delete an IVR tree",
        description = "Soft-deletes a tree. Prefer archiving: deletion is for a tree that was never "
            + "used, because a soft delete is intended to retire a resource while leaving its "
            + "history intact. Captured execution snapshots remain self-contained and executable "
            + "either way.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204",
        description = "Tree deleted")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
        description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
        description = "Missing IVR_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
        description = "Not found, or outside the caller's boundary")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ivrTreeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
