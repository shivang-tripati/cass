package com.shivang.obd.audio;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.PaginationMetadata;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.audio.dto.AudioAssetResponse;
import com.shivang.obd.audio.dto.CreateAudioAssetRequest;
import com.shivang.obd.audio.dto.UpdateAudioAssetRequest;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audio asset application service. Authorization is enforced at this
 * boundary from the server-derived organizational context; tenant callers
 * operate only inside their own tenant, reseller visibility follows the
 * active-tenant hierarchy, platform scope is unbounded. Scoped lookups
 * make foreign and nonexistent assets indistinguishable (404).
 */
@Service
@RequiredArgsConstructor
public class AudioAssetService {

    private static final String CAP_VIEW = "AUDIO_VIEW";
    private static final String CAP_MANAGE = "AUDIO_MANAGE";
    /** Pre-seeded in V1: "Approve or reject uploaded audio". */
    private static final String CAP_APPROVE = "AUDIO_APPROVE";

    private static final List<String> SORTABLE_FIELDS =
        List.of("name", "fileName", "createdAt", "updatedAt", "status");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final AudioAssetRepository repository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final AudioAssetMapper mapper;
    private final TenantRepository tenantRepository;
    private final AudioStorage audioStorage;
    private final AudioUploadValidator uploadValidator;

    @Transactional
    public ApiResponse<AudioAssetResponse> create(CreateAudioAssetRequest request) {
        UUID userId = requireUserId();
        Scope scope = currentScope();
        UUID tenantId = scope.tenantId();
        if (tenantId == null) {
            throw business("A tenant must be specified for this operation.");
        }
        requireTenantUsable(tenantId);
        authorizationService.requireCapability(userId, CAP_MANAGE, AccessCheck.forTenant(tenantId));

        AudioAssetEntity saved = repository.save(mapper.toEntity(request, tenantId));
        return ResponseFactory.created(mapper.toResponse(saved));
    }

    @Transactional(readOnly = true)
    public ApiResponse<AudioAssetResponse> getById(UUID audioAssetId) {
        UUID userId = requireUserId();
        AudioAssetEntity entity = findVisible(audioAssetId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_VIEW, AccessCheck.forTenant(entity.getTenantId()));
        return ResponseFactory.ok(mapper.toResponse(entity));
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<AudioAssetResponse>> list(
        int page, int size, String[] sortArr, String statusFilter, String search
    ) {
        UUID userId = requireUserId();
        Scope scope = currentScope();

        Specification<AudioAssetEntity> boundary;
        if (scope.tenantId() != null) {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.forTenant(scope.tenantId()));
            boundary = AudioAssetSpecifications.forTenant(scope.tenantId());
        } else if (scope.resellerId() != null) {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.forReseller(scope.resellerId()));
            List<UUID> hierarchyTenants = hierarchyTenantIds(scope.resellerId());
            if (hierarchyTenants.isEmpty()) {
                return ResponseFactory.page(List.of(), PaginationMetadata.of(page, size, 0));
            }
            boundary = AudioAssetSpecifications.forTenants(hierarchyTenants);
        } else {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.platformWide());
            boundary = null;
        }

        Specification<AudioAssetEntity> specification = AudioAssetSpecifications.compose(
            AudioAssetSpecifications.notDeleted(),
            boundary,
            statusFilter(statusFilter),
            AudioAssetSpecifications.search(search));

        Page<AudioAssetEntity> resultPage =
            repository.findAll(specification, buildPageable(page, size, sortArr));
        List<AudioAssetResponse> items = resultPage.getContent().stream()
            .map(mapper::toResponse).toList();
        return ResponseFactory.page(items, PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    @Transactional
    public ApiResponse<AudioAssetResponse> update(UUID audioAssetId, UpdateAudioAssetRequest request) {
        UUID userId = requireUserId();
        AudioAssetEntity entity = findVisible(audioAssetId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_MANAGE, AccessCheck.forTenant(entity.getTenantId()));

        mapper.updateEntity(entity, request);
        AudioAssetEntity saved = repository.save(entity);
        return ResponseFactory.ok(mapper.toResponse(saved));
    }

    /** Soft delete: preserves the row and stamps both deletion audit columns. */
    @Transactional
    public void delete(UUID audioAssetId) {
        UUID userId = requireUserId();
        AudioAssetEntity entity = findVisible(audioAssetId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_MANAGE, AccessCheck.forTenant(entity.getTenantId()));

        Instant now = Instant.now();
        entity.setDeletedAt(now);
        entity.setDeletedBy(userId.toString());
        repository.save(entity);
    }

    /** Approves an asset so campaigns may reference it (AUDIO_APPROVE capability). */
    @Transactional
    public ApiResponse<AudioAssetResponse> approve(UUID audioAssetId) {
        return transition(audioAssetId, AudioAssetStatus.APPROVED);
    }

    /** Rejects an asset; existing campaign references fail at activation. */
    @Transactional
    public ApiResponse<AudioAssetResponse> reject(UUID audioAssetId) {
        return transition(audioAssetId, AudioAssetStatus.REJECTED);
    }

    /**
     * Multipart audio upload (VB-5B). The client supplies name, description
     * and the binary file; the backend derives content type (validated
     * against magic bytes), size, SHA-256 checksum, best-effort duration and
     * the storage reference. Ownership comes exclusively from the
     * server-derived organizational context — never from client input.
     * <p>
     * Uploaded assets always start {@link AudioAssetStatus#PENDING_APPROVAL}:
     * upload never approves. Ordering/compensation design (filesystem is not
     * transactional with PostgreSQL):
     * <ol>
     *   <li>validate + extract metadata from the upload stream</li>
     *   <li>assign the entity id eagerly, persist the row (file path layout
     *       depends on the id; the row initially has no storage reference and
     *       is not usable — satisfying "DB exists, file does not ⇒ unusable")</li>
     *   <li>store the bytes through {@link AudioStorage}</li>
     *   <li>persist the storage reference; on failure, compensate by deleting
     *       the orphaned physical file and rethrow (the transaction rolls
     *       back, removing the metadata row)</li>
     * </ol>
     * A crash between steps leaves an orphaned file (cleaned by the next
     * failed-upload compensation on the same asset) or a reference-less
     * PENDING_APPROVAL row (not usable by campaigns) — both safe states.
     */
    @Transactional
    public ApiResponse<AudioAssetResponse> upload(
        String name,
        String description,
        String originalFileName,
        String contentType,
        java.io.InputStream content
    ) {
        UUID userId = requireUserId();
        UUID tenantId = currentScope().tenantId();
        if (tenantId == null) {
            throw business("A tenant must be specified for this operation.");
        }
        requireTenantUsable(tenantId);
        authorizationService.requireCapability(userId, CAP_MANAGE, AccessCheck.forTenant(tenantId));

        if (name == null || name.isBlank()) {
            throw business("Audio asset name is required.");
        }
        AudioUploadValidator.ValidatedAudio validated =
            uploadValidator.validate(contentType, originalFileName, content);

        AudioAssetEntity entity = new AudioAssetEntity();
        entity.setTenantId(tenantId);
        entity.setName(name.trim());
        entity.setDescription(description == null || description.isBlank() ? null : description.trim());
        entity.setFileName(sanitizeOriginalFileName(originalFileName));
        entity.setContentType(validated.contentType());
        entity.setFileSize(validated.sizeBytes());
        entity.setDurationSeconds(validated.durationSeconds());
        entity.setChecksum(validated.sha256Hex());
        entity.setStatus(AudioAssetStatus.PENDING_APPROVAL);
        // First save leaves the id generation to @UuidGenerator: with a
        // manually pre-assigned id, Spring Data's save() would take the
        // merge path for a still-transient row and fail. After this save
        // the entity carries its identity, which the storage layout uses.
        repository.save(entity);

        String storageReference = null;
        try {
            AudioStorage.StoredAudio stored = audioStorage.store(
                tenantId, entity.getId(), originalFileName,
                new java.io.ByteArrayInputStream(validated.content()));
            storageReference = stored.storageReference();
            if (stored.sizeBytes() != validated.sizeBytes()) {
                throw new AudioStorageException(
                    "Stored audio size " + stored.sizeBytes() + " does not match validated upload size "
                        + validated.sizeBytes());
            }
        } catch (RuntimeException e) {
            // Compensation: remove the orphaned physical file, if any, then let
            // the transaction roll back the metadata row.
            if (storageReference != null) {
                audioStorage.delete(storageReference);
            }
            if (e instanceof InvalidAudioUploadException) {
                throw e;
            }
            throw new AudioStorageException(
                "Audio storage failed; the asset was not registered: " + e.getMessage(), e);
        }

        entity.setStorageReference(storageReference);
        AudioAssetEntity saved = repository.save(entity);
        return ResponseFactory.created(mapper.toResponse(saved));
    }

    /** Server-side safe display name: basename only, separators stripped. */
    private static String sanitizeOriginalFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "audio.bin";
        }
        String base = originalFileName.replace('\\', '/')
            .substring(originalFileName.replace('\\', '/').lastIndexOf('/') + 1)
            .trim();
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) {
            return "audio.bin";
        }
        return base;
    }

    // === internal ===

    private ApiResponse<AudioAssetResponse> transition(UUID audioAssetId, AudioAssetStatus target) {
        UUID userId = requireUserId();
        AudioAssetEntity entity = findVisible(audioAssetId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_APPROVE, AccessCheck.forTenant(entity.getTenantId()));

        if (entity.getStatus() == target) {
            throw new ConflictException("Audio asset is already " + target + ".");
        }
        entity.setStatus(target);
        AudioAssetEntity saved = repository.save(entity);
        return ResponseFactory.ok(mapper.toResponse(saved));
    }

    private record Scope(UUID tenantId, UUID resellerId) {

        static Scope of(com.shivang.obd.authz.context.OrganizationContext ctx) {
            return ctx == null
                ? new Scope(null, null)
                : new Scope(ctx.tenantId(), ctx.resellerId());
        }
    }

    private Scope currentScope() {
        return Scope.of(OrganizationContextHolder.current().orElse(null));
    }

    /**
     * TENANT callers use query-level scoping; RESELLER/PLATFORM load the
     * live row and resolve visibility before any capability decision —
     * every out-of-scope outcome is the same 404.
     */
    private AudioAssetEntity findVisible(UUID audioAssetId, Scope scope) {
        if (scope.tenantId() != null) {
            return repository.findByIdAndTenantIdAndDeletedAtIsNull(audioAssetId, scope.tenantId())
                .orElseThrow(AudioAssetService::notFound);
        }
        AudioAssetEntity entity = repository.findByIdAndDeletedAtIsNull(audioAssetId)
            .orElseThrow(AudioAssetService::notFound);
        if (scope.resellerId() != null
            && !hierarchyTenantIds(scope.resellerId()).contains(entity.getTenantId())) {
            throw notFound();
        }
        return entity;
    }

    private List<UUID> hierarchyTenantIds(UUID resellerId) {
        return tenantRepository.findAllByResellerIdAndStatus(
                resellerId, com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE)
            .stream()
            .map(TenantEntity::getId)
            .toList();
    }

    private void requireTenantUsable(UUID tenantId) {
        TenantEntity tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
            .orElseThrow(() -> business("Referenced tenant does not exist or is not usable."));
        if (tenant.getStatus() != com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE) {
            throw business("Referenced tenant does not exist or is not usable.");
        }
    }

    private Specification<AudioAssetEntity> statusFilter(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) {
            return null;
        }
        try {
            return AudioAssetSpecifications.hasStatus(parseEnum(statusStr, AudioAssetStatus.values()));
        } catch (IllegalArgumentException ex) {
            throw business("Unknown status filter: " + statusStr);
        }
    }

    private <E extends Enum<E>> E parseEnum(String value, E[] values) {
        String normalized = value.trim().toUpperCase().replace('-', '_');
        for (E candidate : values) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(value);
    }

    private Pageable buildPageable(int page, int size, String[] sortArr) {
        Sort sort = DEFAULT_SORT;
        if (sortArr != null && sortArr.length > 0 && SORTABLE_FIELDS.contains(sortArr[0])) {
            Sort.Direction direction = sortArr.length > 1 && "asc".equalsIgnoreCase(sortArr[1])
                ? Sort.Direction.ASC : Sort.Direction.DESC;
            sort = Sort.by(direction, sortArr[0]);
        }
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort);
    }

    private UUID requireUserId() {
        return currentUserProvider.current()
            .orElseThrow(() -> new BusinessException(
                CommonErrorCode.UNAUTHORIZED, "Authentication required."))
            .userId();
    }

    private BusinessException business(String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_ERROR, message);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Audio asset not found");
    }
}
