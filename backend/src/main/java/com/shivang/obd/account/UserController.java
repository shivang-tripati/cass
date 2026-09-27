package com.shivang.obd.account;

import com.shivang.obd.account.dto.UpdateUserRequest;
import com.shivang.obd.account.dto.UserResponse;
import com.shivang.obd.common.api.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User management within the caller's organizational boundary")
public class UserController {

    private final UserService userService;

    @Operation(
        summary = "Get a user",
        description = "Authorization is derived from the TARGET user's organizational home: "
            + "SUPER_ADMIN sees everyone, RESELLER_ADMIN its hierarchy, TENANT_ADMIN its own "
            + "tenant users. Cross-boundary access returns 403 without leaking existence.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Outside caller scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/{id}")
    public ApiResponse<UserResponse> getById(@PathVariable UUID id) {
        return userService.getById(id);
    }

    @Operation(
        summary = "List users",
        description = "Paginated, sortable and searchable; results are implicitly scoped to "
            + "the caller's organizational context (own tenant / own reseller hierarchy / platform).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter/sort")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing capability")
    @GetMapping
    public ApiResponse<List<UserResponse>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt,desc") String[] sort,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String search
    ) {
        return userService.list(page, size, sort, status, search);
    }

    @Operation(
        summary = "Update user profile/status",
        description = "Partial update of displayName and status within the target's "
            + "organizational boundary. Email, credentials, roles and organizational bindings "
            + "are not mutable here.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Outside caller scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found")
    @PutMapping("/{id}")
    public ApiResponse<UserResponse> update(
        @PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request
    ) {
        return userService.update(id, request);
    }
}
