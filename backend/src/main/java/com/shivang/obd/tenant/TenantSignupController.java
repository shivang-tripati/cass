package com.shivang.obd.tenant;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.tenant.dto.TenantResponse;
import com.shivang.obd.tenant.dto.TenantSignupRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous direct-tenant self-signup. Creates a standalone tenant
 * (reseller_id NULL) with its TENANT_ADMIN owner; no role, organization or
 * hierarchy selector is accepted from the client.
 */
@RestController
@RequestMapping("/api/v1/account/signup")
@RequiredArgsConstructor
@Tag(name = "Signup", description = "Anonymous self-service account creation")
public class TenantSignupController {

    private final TenantProvisioningService provisioningService;

    @Operation(
        summary = "Self-signup as a direct tenant",
        description = "Creates a new tenant without a reseller together with its TENANT_ADMIN "
            + "account in one transaction. Anonymous. Fails with 409 on duplicate slug or email."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Tenant created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Duplicate slug/email")
    @PostMapping("/tenant")
    public ResponseEntity<ApiResponse<TenantResponse>> signupTenant(
        @Valid @RequestBody TenantSignupRequest request
    ) {
        var body = provisioningService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
