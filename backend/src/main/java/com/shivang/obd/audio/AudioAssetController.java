package com.shivang.obd.audio;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.audio.dto.AudioAssetResponse;
import com.shivang.obd.audio.dto.CreateAudioAssetRequest;
import com.shivang.obd.audio.dto.UpdateAudioAssetRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
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
@RequestMapping("/api/v1/audio-assets")
@RequiredArgsConstructor
@Tag(name = "Audio Assets", description = "Tenant-owned audio assets that campaigns reference "
    + "by identifier. Listing and reads are implicitly scoped to the caller's organizational "
    + "boundary. Uploads (WAV/MP3) are stored through the audio storage seam and start in "
    + "PENDING_APPROVAL state; this phase manages the approved content registry.")
public class AudioAssetController {

    private final AudioAssetService audioAssetService;

    @Operation(
        summary = "Register an audio asset",
        description = "Registers asset metadata in PENDING_APPROVAL state within the caller's "
            + "context tenant. Campaigns may only use APPROVED assets.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Asset registered")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing AUDIO_MANAGE capability")
    @PostMapping
    public ResponseEntity<ApiResponse<AudioAssetResponse>> create(
        @Valid @RequestBody CreateAudioAssetRequest request
    ) {
        ApiResponse<AudioAssetResponse> body = audioAssetService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "Get an audio asset",
        description = "Scoped lookups constrain access to the caller's boundary, so a foreign "
            + "asset and a nonexistent asset are indistinguishable (404).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing AUDIO_VIEW capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @GetMapping("/{id}")
    public ApiResponse<AudioAssetResponse> getById(@PathVariable UUID id) {
        return audioAssetService.getById(id);
    }

    @Operation(
        summary = "Upload an audio asset",
        description = "Multipart upload (name, description, file). The backend derives content type, "
            + "size, SHA-256 checksum, best-effort duration and the storage reference; the client "
            + "never supplies them. Assets are created in PENDING_APPROVAL state; campaigns may only "
            + "use APPROVED assets. Supported formats: WAV, MP3.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Asset uploaded")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Unsupported/invalid audio, oversized file, or validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing AUDIO_MANAGE capability")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Audio storage is disabled")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AudioAssetResponse>> upload(
        @RequestParam("name") String name,
        @RequestParam(value = "description", required = false) String description,
        @RequestParam("file") MultipartFile file
    ) {
        ApiResponse<AudioAssetResponse> body;
        try (java.io.InputStream content = file.getInputStream()) {
            body = audioAssetService.upload(
                name, description, file.getOriginalFilename(), file.getContentType(), content);
        } catch (java.io.IOException e) {
            throw new InvalidAudioUploadException("Cannot read uploaded audio: " + e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(
        summary = "List audio assets",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid filter or sort")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing AUDIO_VIEW capability")
    @GetMapping
    public ApiResponse<List<AudioAssetResponse>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "createdAt,desc") String[] sort,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String search
    ) {
        return audioAssetService.list(page, size, sort, status, search);
    }

    @Operation(
        summary = "Update audio asset metadata",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing AUDIO_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @PutMapping("/{id}")
    public ApiResponse<AudioAssetResponse> update(
        @PathVariable UUID id, @Valid @RequestBody UpdateAudioAssetRequest request
    ) {
        return audioAssetService.update(id, request);
    }

    @Operation(
        summary = "Delete an audio asset",
        description = "Soft delete; existing campaign references fail activation afterwards.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing AUDIO_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        audioAssetService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Approve an audio asset",
        description = "Requires the AUDIO_APPROVE capability on the owning tenant.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approved")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing AUDIO_APPROVE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already approved")
    @PatchMapping("/{id}/approve")
    public ApiResponse<AudioAssetResponse> approve(@PathVariable UUID id) {
        return audioAssetService.approve(id);
    }

    @Operation(
        summary = "Reject an audio asset",
        description = "Requires the AUDIO_APPROVE capability on the owning tenant.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Rejected")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing AUDIO_APPROVE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found or outside caller boundary")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already rejected")
    @PatchMapping("/{id}/reject")
    public ApiResponse<AudioAssetResponse> reject(@PathVariable UUID id) {
        return audioAssetService.reject(id);
    }
}
