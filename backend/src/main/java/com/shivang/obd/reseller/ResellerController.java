package com.shivang.obd.reseller;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.reseller.dto.CreateResellerRequest;
import com.shivang.obd.reseller.dto.ResellerResponse;
import com.shivang.obd.reseller.dto.UpdateResellerRequest;
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
@RequestMapping("/api/v1/resellers")
@RequiredArgsConstructor
@Tag(name = "Resellers", description = "Reseller administration and provisioning")
public class ResellerController {

    private final ResellerService resellerService;

    @Operation(
        summary = "Create a reseller with its initial admin account",
        description = "SUPER_ADMIN only. Atomically provisions the reseller, its "
            + "RESELLER_ADMIN user, credential, organizational home and membership. "
            + "Fails with 409 on duplicate slug or custom domain.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Reseller provisioned")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Duplicate slug/custom domain/email")
    @PostMapping
    public ResponseEntity<ApiResponse<ResellerResponse>> create(
        @Valid @RequestBody CreateResellerRequest request
    ) {
        var body = resellerService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "Get a reseller",
        description = "SUPER_ADMIN sees any reseller; RESELLER_ADMIN is restricted to its own.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Outside caller scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/{id}")
    public ApiResponse<ResellerResponse> getById(@PathVariable UUID id) {
        return resellerService.getById(id);
    }

    @Operation(
        summary = "List resellers",
        description = "Paginated, sortable and searchable. RESELLER_ADMIN results are "
            + "implicitly scoped to the caller's own reseller.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter/sort")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing capability")
    @GetMapping
    public ApiResponse<List<ResellerResponse>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt,desc") String[] sort,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String search
    ) {
        return resellerService.list(page, size, sort, status, search);
    }

    @Operation(
        summary = "Update a reseller",
        description = "SUPER_ADMIN (any reseller) or the owning RESELLER_ADMIN. "
            + "Slug and custom domain are immutable.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Outside caller scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found")
    @PutMapping("/{id}")
    public ApiResponse<ResellerResponse> update(
        @PathVariable UUID id, @Valid @RequestBody UpdateResellerRequest request
    ) {
        return resellerService.update(id, request);
    }

    @Operation(
        summary = "Deactivate (soft delete) a reseller",
        description = "SUPER_ADMIN only. Marks the reseller deleted without physical removal.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        resellerService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
