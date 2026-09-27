package com.shivang.obd.did;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.did.dto.AssignDidRequest;
import com.shivang.obd.did.dto.AssignDidResponse;
import com.shivang.obd.did.dto.CreateDidRequest;
import com.shivang.obd.did.dto.DidResponse;
import com.shivang.obd.did.dto.UpdateDidRequest;
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

@RestController
@RequestMapping("/api/v1/dids")
@RequiredArgsConstructor
@Tag(name = "DIDs", description = "Managed phone number (DID) inventory. Listing and reads "
    + "are implicitly scoped to the caller's organizational boundary (own tenant / own reseller "
    + "pool and managed tenants / platform).")
public class DidController {

    private final DidService didService;

    @Operation(
        summary = "Register a DID",
        description = "Registers a canonical E.164 number. Ownership is derived from the "
            + "caller's organizational context: tenant callers register under their own tenant, "
            + "reseller callers may target their pool or a tenant inside their managed hierarchy, "
            + "and platform callers may target any validated organization. Duplicate live E.164 "
            + "numbers are rejected with 409.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "DID registered")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed / invalid ownership combination")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing DID_MANAGE capability for the target organization")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Duplicate E.164 number")
    @PostMapping
    public ResponseEntity<ApiResponse<DidResponse>> create(
        @Valid @RequestBody CreateDidRequest request
    ) {
        ApiResponse<DidResponse> body = didService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "Get a DID by id",
        description = "Scoped lookups constrain access to the caller's boundary, so a foreign "
            + "DID and a nonexistent DID are indistinguishable (404).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing DID_VIEW capability for the owning organization")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @GetMapping("/{id}")
    public ApiResponse<DidResponse> getById(@PathVariable UUID id) {
        return didService.getById(id);
    }

    @Operation(
        summary = "List DIDs",
        description = "Paginated, sortable and searchable listing scoped to the caller's "
            + "organizational context. Supports filters: status, allocationState, numberType, "
            + "provider, circle, search. The tenant and reseller parameters are honored only "
            + "for platform-scope callers. Soft-deleted DIDs are always excluded.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter or sort")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing DID_VIEW capability")
    @GetMapping
    public ApiResponse<List<DidResponse>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt,desc") String[] sort,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String allocationState,
        @RequestParam(required = false) String numberType,
        @RequestParam(required = false) String provider,
        @RequestParam(required = false) String circle,
        @RequestParam(required = false) UUID tenantId,
        @RequestParam(required = false) UUID resellerId,
        @RequestParam(required = false) String q
    ) {
        return didService.list(page, size, sort, status, allocationState, numberType,
            provider, circle, tenantId, resellerId, q);
    }

    @Operation(
        summary = "Update a DID",
        description = "Replaces mutable configuration (PUT semantics: omitted optional fields "
            + "are cleared). The E.164 number and ownership are immutable; ASSIGNED requires an "
            + "assigned tenant.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed / invalid state combination")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing DID_MANAGE capability for the owning organization")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @PutMapping("/{id}")
    public ApiResponse<DidResponse> update(
        @PathVariable UUID id, @Valid @RequestBody UpdateDidRequest request
    ) {
        return didService.update(id, request);
    }

    @Operation(
        summary = "Assign a DID",
        description = "Assigns an AVAILABLE DID to a target organization. Platform callers may "
            + "assign platform-pool DIDs to a reseller pool or directly to a tenant; reseller "
            + "callers may assign only their own pool DIDs to their own active tenants. An "
            + "ASSIGNED DID must be revoked before it can be assigned again.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Assigned")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid target or inactive DID")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing DID_MANAGE capability")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DID not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DID not available / already assigned")
    @PostMapping("/{id}/assign")
    public ApiResponse<AssignDidResponse> assign(
        @PathVariable UUID id, @Valid @RequestBody AssignDidRequest request
    ) {
        return didService.assign(id, request);
    }

    @Operation(
        summary = "Revoke a DID assignment",
        description = "Revokes a live assignment and restores the DID to its original allocation "
            + "pool (platform or reseller, per provenance). Only AVAILABLE DIDs can be assigned again.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Revoked")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Inactive DID")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing DID_MANAGE capability")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DID not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DID is not assigned")
    @PostMapping("/{id}/revoke")
    public ApiResponse<AssignDidResponse> revoke(@PathVariable UUID id) {
        return didService.revoke(id);
    }

    @Operation(
        summary = "Delete a DID",
        description = "Soft delete: the row is preserved with deletion audit columns stamped "
            + "and disappears from all normal queries; its E.164 number becomes reusable.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing DID_MANAGE capability for the owning organization")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        didService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

