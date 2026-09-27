package com.shivang.obd.tenant;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.tenant.dto.CreateAgentRequest;
import com.shivang.obd.tenant.dto.CreateTenantRequest;
import com.shivang.obd.tenant.dto.TenantResponse;
import com.shivang.obd.tenant.dto.UpdateTenantRequest;
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
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Tenants", description = "Tenant administration, provisioning and agent accounts")
public class TenantController {

    private final TenantService tenantService;

    @Operation(
        summary = "Provision a tenant with its initial admin account",
        description = "Atomically creates a TENANT plus its TENANT_ADMIN user, credential, "
            + "organizational home and membership. RESELLER_ADMIN callers are locked to their "
            + "own reseller (server-derived); a client-supplied resellerId that deviates is "
            + "rejected with 403. Only SUPER_ADMIN may create direct tenants (resellerId NULL) "
            + "or select a target reseller. Fails with 409 on duplicate slug or email.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Tenant provisioned")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing capability or foreign-reseller attempt")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Referenced reseller not found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Duplicate slug/email/inactive reseller")
    @PostMapping
    public ResponseEntity<ApiResponse<TenantResponse>> create(
        @Valid @RequestBody CreateTenantRequest request
    ) {
        var body = tenantService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "Get a tenant",
        description = "SUPER_ADMIN sees any tenant; RESELLER_ADMIN only tenants of its own "
            + "hierarchy; TENANT_ADMIN only its own tenant.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Outside caller scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/{id}")
    public ApiResponse<TenantResponse> getById(@PathVariable UUID id) {
        return tenantService.getById(id);
    }

    @Operation(
        summary = "List tenants",
        description = "Paginated, sortable and searchable. Results are implicitly scoped to "
            + "the caller's organizational context (own tenant / own hierarchy / platform).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter/sort")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing capability")
    @GetMapping
    public ApiResponse<List<TenantResponse>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt,desc") String[] sort,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String search
    ) {
        return tenantService.list(page, size, sort, status, search);
    }

    @Operation(
        summary = "Update a tenant",
        description = "SUPER_ADMIN or the managing RESELLER_ADMIN within the tenant's hierarchy.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Outside caller scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found")
    @PutMapping("/{id}")
    public ApiResponse<TenantResponse> update(
        @PathVariable UUID id, @Valid @RequestBody UpdateTenantRequest request
    ) {
        return tenantService.update(id, request);
    }

    @Operation(
        summary = "Deactivate (soft delete) a tenant",
        description = "SUPER_ADMIN or the managing RESELLER_ADMIN within the tenant's hierarchy.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Outside caller scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        tenantService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Create an AGENT account for a tenant",
        description = "Restricted to SUPER_ADMIN by current functional requirement. Creates a "
            + "tenant-bound user with the ASSIGNED-scoped AGENT role; the role is fixed "
            + "server-side and cannot be overridden.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Agent account created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Duplicate email or inactive tenant")
    @PostMapping("/{tenantId}/agents")
    public ResponseEntity<ApiResponse<TenantResponse>> createAgent(
        @PathVariable UUID tenantId,
        @Valid @RequestBody CreateAgentRequest request
    ) {
        var body = tenantService.createAgent(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
