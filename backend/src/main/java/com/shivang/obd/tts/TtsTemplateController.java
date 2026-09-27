package com.shivang.obd.tts;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.tts.dto.CreateTtsTemplateRequest;
import com.shivang.obd.tts.dto.TtsTemplateResponse;
import com.shivang.obd.tts.dto.UpdateTtsTemplateRequest;
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
@RequestMapping("/api/v1/tts-templates")
@RequiredArgsConstructor
@Tag(name = "TTS Templates", description = "Tenant-owned text-to-speech templates with simple "
    + "{{variable}} placeholders. Listing and reads are implicitly scoped to the caller's "
    + "organizational boundary. Rendering/synthesis belongs to the future execution layer.")
public class TtsTemplateController {

    private final TtsTemplateService ttsTemplateService;

    @Operation(
        summary = "Create a TTS template",
        description = "Creates a template within the caller's context tenant in "
            + "PENDING_APPROVAL state. Platform (SUPER_ADMIN) callers may target any active "
            + "tenant via tenantId and create system/prebuilt templates directly APPROVED. The "
            + "text may only reference declared variables; malformed placeholders and stray "
            + "braces are rejected.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Template created")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed (schema, placeholders, undeclared variables)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing TTS_MANAGE capability")
    @PostMapping
    public ResponseEntity<ApiResponse<TtsTemplateResponse>> create(
        @Valid @RequestBody CreateTtsTemplateRequest request
    ) {
        ApiResponse<TtsTemplateResponse> body = ttsTemplateService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "Get a TTS template",
        description = "Scoped lookups constrain access to the caller's boundary, so a foreign "
            + "template and a nonexistent template are indistinguishable (404).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing TTS_VIEW capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @GetMapping("/{id}")
    public ApiResponse<TtsTemplateResponse> getById(@PathVariable UUID id) {
        return ttsTemplateService.getById(id);
    }

    @Operation(
        summary = "List TTS templates",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter or sort")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing TTS_VIEW capability")
    @GetMapping
    public ApiResponse<List<TtsTemplateResponse>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt,desc") String[] sort,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String search
    ) {
        return ttsTemplateService.list(page, size, sort, status, search);
    }

    @Operation(
        summary = "Update a TTS template",
        description = "Replaces mutable content. Editing an APPROVED template returns it to "
            + "PENDING_APPROVAL for re-review.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing TTS_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @PutMapping("/{id}")
    public ApiResponse<TtsTemplateResponse> update(
        @PathVariable UUID id, @Valid @RequestBody UpdateTtsTemplateRequest request
    ) {
        return ttsTemplateService.update(id, request);
    }

    @Operation(
        summary = "Delete a TTS template",
        description = "Soft delete; existing campaign references fail activation afterwards.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing TTS_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ttsTemplateService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Approve a TTS template",
        description = "Requires the TTS_APPROVE capability on the owning tenant.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approved")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing TTS_APPROVE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already approved")
    @PatchMapping("/{id}/approve")
    public ApiResponse<TtsTemplateResponse> approve(@PathVariable UUID id) {
        return ttsTemplateService.approve(id);
    }

    @Operation(
        summary = "Reject a TTS template",
        description = "Requires the TTS_APPROVE capability on the owning tenant.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Rejected")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing TTS_APPROVE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already rejected")
    @PatchMapping("/{id}/reject")
    public ApiResponse<TtsTemplateResponse> reject(@PathVariable UUID id) {
        return ttsTemplateService.reject(id);
    }
}
