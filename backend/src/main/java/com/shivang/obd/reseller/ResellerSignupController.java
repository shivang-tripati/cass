package com.shivang.obd.reseller;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.reseller.dto.CreateResellerRequest;
import com.shivang.obd.reseller.dto.ResellerResponse;
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
 * Anonymous reseller self-signup. Creates ONLY a reseller with its
 * RESELLER_ADMIN owner; callers can never select a role, an organization
 * id or any other privilege escalation vector — everything else is derived
 * server-side.
 */
@RestController
@RequestMapping("/api/v1/account/signup")
@RequiredArgsConstructor
@Tag(name = "Signup", description = "Anonymous self-service account creation")
public class ResellerSignupController {

    private final ResellerProvisioningService provisioningService;

    @Operation(
        summary = "Self-signup as a reseller",
        description = "Creates a new reseller together with its RESELLER_ADMIN account "
            + "in one transaction. Anonymous. Fails with 409 on duplicate slug, "
            + "custom domain or email."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Reseller created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Duplicate slug/custom domain/email")
    @PostMapping("/reseller")
    public ResponseEntity<ApiResponse<ResellerResponse>> signupReseller(
        @Valid @RequestBody CreateResellerRequest request
    ) {
        var body = provisioningService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
