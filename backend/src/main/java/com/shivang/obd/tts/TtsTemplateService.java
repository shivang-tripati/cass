package com.shivang.obd.tts;

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
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.tts.dto.CreateTtsTemplateRequest;
import com.shivang.obd.tts.dto.TtsTemplateResponse;
import com.shivang.obd.tts.dto.UpdateTtsTemplateRequest;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
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
 * TTS template application service. Authorization is enforced at this
 * boundary from the server-derived organizational context. Platform
 * (SUPER_ADMIN) callers create templates directly in APPROVED state
 * (system/prebuilt); tenant-created templates start PENDING_APPROVAL.
 *
 * <p>Scope governance (V42): GLOBAL rows are platform-owned — minted only
 * with platform-scope TTS_MANAGE and never on behalf of a tenant — and are
 * managed and gated (update/delete/approve/reject) only via
 * {@link AccessCheck#platformWide()}. An APPROVED, non-deleted GLOBAL
 * template is visible to every caller and usable by every tenant. TENANT
 * rows keep the pre-VB-5D rules, including the platform seeding path into
 * a target tenant. GLOBAL is never copied into tenants and never carries
 * a tenant stamp.
 */
@Service
@RequiredArgsConstructor
public class TtsTemplateService {

    private static final String CAP_VIEW = "TTS_VIEW";
    private static final String CAP_MANAGE = "TTS_MANAGE";
    private static final String CAP_APPROVE = "TTS_APPROVE";

    private static final List<String> SORTABLE_FIELDS =
        List.of("name", "createdAt", "updatedAt", "status");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final TtsTemplateRepository repository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final TtsTemplateMapper mapper;
    private final TenantRepository tenantRepository;

    @Transactional
    public ApiResponse<TtsTemplateResponse> create(CreateTtsTemplateRequest request) {
        UUID userId = requireUserId();
        Scope scope = currentScope();

        TtsTemplateScope requestedScope =
            request.scope() == null ? TtsTemplateScope.TENANT : request.scope();

        UUID tenantId;
        boolean platformCreator;
        if (requestedScope == TtsTemplateScope.GLOBAL) {
            // GLOBAL templates are platform-owned: they never live inside a
            // tenant and cannot be created on behalf of one (a tenant stamp
            // would defeat the V42 scope/tenancy invariants).
            if (request.tenantId() != null) {
                throw business("GLOBAL templates are platform-owned and must not reference a tenant.");
            }
            tenantId = null;
            platformCreator = true;
        } else if (scope.tenantId() != null) {
            tenantId = scope.tenantId();
            platformCreator = false;
        } else if (request.tenantId() != null) {
            // Platform callers may seed system templates into any active tenant.
            TenantEntity target = tenantRepository.findByIdAndDeletedAtIsNull(request.tenantId())
                .orElseThrow(() -> business("Referenced tenant does not exist or is not usable."));
            if (target.getStatus() != LifecycleStatus.ACTIVE) {
                throw business("Referenced tenant does not exist or is not usable.");
            }
            tenantId = target.getId();
            platformCreator = true;
        } else {
            throw business("A tenant must be specified for this operation.");
        }

        authorizationService.requireCapability(
            userId, CAP_MANAGE,
            platformCreator ? AccessCheck.platformWide() : AccessCheck.forTenant(tenantId));

        validateContract(request.templateText(), request.variables());

        // System/prebuilt templates created by the platform are approved by default.
        TtsTemplateEntity entity = mapper.toEntity(request, tenantId,
            platformCreator ? TtsTemplateStatus.APPROVED : TtsTemplateStatus.PENDING_APPROVAL);
        TtsTemplateEntity saved = repository.save(entity);
        return ResponseFactory.created(mapper.toResponse(saved));
    }

    @Transactional(readOnly = true)
    public ApiResponse<TtsTemplateResponse> getById(UUID templateId) {
        UUID userId = requireUserId();
        Scope scope = currentScope();
        TtsTemplateEntity entity = findVisible(templateId, scope);
        authorizationService.requireCapability(userId, CAP_VIEW, viewCheckFor(scope, entity));
        return ResponseFactory.ok(mapper.toResponse(entity));
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<TtsTemplateResponse>> list(
        int page, int size, String[] sortArr, String statusFilter, String search
    ) {
        UUID userId = requireUserId();
        Scope scope = currentScope();

        Specification<TtsTemplateEntity> boundary;
        if (scope.tenantId() != null) {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.forTenant(scope.tenantId()));
            // Own TENANT rows plus the APPROVED GLOBAL catalog; never other
            // tenants' rows. Deliberately not equivalent to campaign
            // usability: own unapproved rows stay manageable here.
            boundary = TtsTemplateSpecifications.visibleToTenant(scope.tenantId());
        } else if (scope.resellerId() != null) {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.forReseller(scope.resellerId()));
            List<UUID> hierarchyTenants = hierarchyTenantIds(scope.resellerId());
            if (hierarchyTenants.isEmpty()) {
                // No hierarchy tenants under management: the reseller still
                // sees the shared APPROVED GLOBAL catalog (read-only).
                boundary = TtsTemplateSpecifications.visibleGlobalOnly();
            } else {
                boundary = TtsTemplateSpecifications.visibleToTenants(hierarchyTenants);
            }
        } else {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.platformWide());
            boundary = null;
        }

        Specification<TtsTemplateEntity> specification = TtsTemplateSpecifications.compose(
            TtsTemplateSpecifications.notDeleted(),
            boundary,
            statusFilter(statusFilter),
            TtsTemplateSpecifications.search(search));

        Page<TtsTemplateEntity> resultPage =
            repository.findAll(specification, buildPageable(page, size, sortArr));
        List<TtsTemplateResponse> items = resultPage.getContent().stream()
            .map(mapper::toResponse).toList();
        return ResponseFactory.page(items, PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    /**
     * Replaces mutable template content. Editing an APPROVED template does
     * not silently keep approval when content changes: the template
     * returns to PENDING_APPROVAL for re-review (GLOBAL included — a
     * platform edit re-enters the platform gate).
     */
    @Transactional
    public ApiResponse<TtsTemplateResponse> update(UUID templateId, UpdateTtsTemplateRequest request) {
        UUID userId = requireUserId();
        TtsTemplateEntity entity = findVisible(templateId, currentScope());
        authorizationService.requireCapability(userId, CAP_MANAGE, manageCheckFor(entity));

        validateContract(request.templateText(), request.variables());

        boolean contentChanged = !entity.getTemplateText().equals(request.templateText())
            || !java.util.Objects.equals(toSchemaSet(entity.getVariables()), toSchemaSet(request.variables()));
        mapper.updateEntity(entity, request);
        if (contentChanged && entity.getStatus() == TtsTemplateStatus.APPROVED) {
            entity.setStatus(TtsTemplateStatus.PENDING_APPROVAL);
        }
        TtsTemplateEntity saved = repository.save(entity);
        return ResponseFactory.ok(mapper.toResponse(saved));
    }

    /** Soft delete: preserves the row and stamps both deletion audit columns. */
    @Transactional
    public void delete(UUID templateId) {
        UUID userId = requireUserId();
        TtsTemplateEntity entity = findVisible(templateId, currentScope());
        authorizationService.requireCapability(userId, CAP_MANAGE, manageCheckFor(entity));

        Instant now = Instant.now();
        entity.setDeletedAt(now);
        entity.setDeletedBy(userId.toString());
        repository.save(entity);
    }

    /** Approves a template for campaign use (TTS_APPROVE capability). */
    @Transactional
    public ApiResponse<TtsTemplateResponse> approve(UUID templateId) {
        return transition(templateId, TtsTemplateStatus.APPROVED);
    }

    /** Rejects a template; existing campaign references fail at activation. */
    @Transactional
    public ApiResponse<TtsTemplateResponse> reject(UUID templateId) {
        return transition(templateId, TtsTemplateStatus.REJECTED);
    }

    // === internal ===

    private ApiResponse<TtsTemplateResponse> transition(UUID templateId, TtsTemplateStatus target) {
        UUID userId = requireUserId();
        TtsTemplateEntity entity = findVisible(templateId, currentScope());
        // GLOBAL gating is platform-only: the pre-VB-5D forTenant(null)
        // check could never be satisfied for tenantless rows.
        authorizationService.requireCapability(userId, CAP_APPROVE, manageCheckFor(entity));

        if (entity.getStatus() == target) {
            throw new ConflictException("TTS template is already " + target + ".");
        }
        entity.setStatus(target);
        TtsTemplateEntity saved = repository.save(entity);
        return ResponseFactory.ok(mapper.toResponse(saved));
    }

    /**
     * Authorization target for a state-changing operation on a template:
     * GLOBAL rows demand platform-wide scope, TENANT rows the owning
     * tenant. Resellers therefore cannot mutate GLOBAL rows even though
     * they can see them.
     */
    private AccessCheck manageCheckFor(TtsTemplateEntity entity) {
        return entity.getScope() == TtsTemplateScope.GLOBAL
            ? AccessCheck.platformWide()
            : AccessCheck.forTenant(entity.getTenantId());
    }

    /**
     * Authorization target for reading a template. Visibility itself is
     * resolved by {@link #findVisible}; GLOBAL rows are a shared catalog,
     * so the caller's own TTS_VIEW boundary applies (tenants and
     * resellers read APPROVED GLOBAL rows without platform scope).
     */
    private AccessCheck viewCheckFor(Scope scope, TtsTemplateEntity entity) {
        if (entity.getScope() == TtsTemplateScope.GLOBAL) {
            if (scope.tenantId() != null) {
                return AccessCheck.forTenant(scope.tenantId());
            }
            if (scope.resellerId() != null) {
                return AccessCheck.forReseller(scope.resellerId());
            }
            return AccessCheck.platformWide();
        }
        return AccessCheck.forTenant(entity.getTenantId());
    }

    /**
     * Deterministic template contract validation. IllegalArgumentException
     * messages are caller-safe domain rules surfaced as 400s.
     */
    private Set<String> validateContract(String templateText, List<TtsTemplateVariable> variables) {
        try {
            Set<String> declaredNames = TtsTemplateValidation.validateSchema(variables);
            TtsTemplateValidation.validateTemplateText(templateText, declaredNames);
            return declaredNames;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, ex.getMessage());
        }
    }

    private Set<String> toSchemaSet(List<TtsTemplateVariable> variables) {
        if (variables == null || variables.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (TtsTemplateVariable v : variables) {
            names.add(v.name());
        }
        return names;
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
     * Load a template the caller is allowed to see, 404-cloaking anything
     * outside their boundary (missing, deleted, foreign-tenant, or
     * not-yet-visible GLOBAL rows).
     */
    private TtsTemplateEntity findVisible(UUID templateId, Scope scope) {
        if (scope.tenantId() != null) {
            // Own TENANT rows plus APPROVED GLOBAL rows (shared catalog).
            // Foreign-tenant rows and unapproved GLOBAL rows resolve to
            // not-found, indistinguishable from a missing id.
            return repository.findByIdAndDeletedAtIsNull(templateId)
                .filter(entity -> (entity.getTenantId() != null
                        && entity.getTenantId().equals(scope.tenantId()))
                    || (entity.getScope() == TtsTemplateScope.GLOBAL
                        && entity.getStatus() == TtsTemplateStatus.APPROVED))
                .orElseThrow(TtsTemplateService::notFound);
        }
        TtsTemplateEntity entity = repository.findByIdAndDeletedAtIsNull(templateId)
            .orElseThrow(TtsTemplateService::notFound);
        if (scope.resellerId() != null
            && entity.getScope() == TtsTemplateScope.TENANT
            && !hierarchyTenantIds(scope.resellerId()).contains(entity.getTenantId())) {
            throw notFound();
        }
        // Platform callers see everything; resellers additionally see
        // GLOBAL rows regardless of which tenant they belong to (GLOBAL
        // rows belong to none).
        return entity;
    }

    private List<UUID> hierarchyTenantIds(UUID resellerId) {
        return tenantRepository.findAllByResellerIdAndStatus(
                resellerId, LifecycleStatus.ACTIVE)
            .stream()
            .map(TenantEntity::getId)
            .toList();
    }

    private Specification<TtsTemplateEntity> statusFilter(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) {
            return null;
        }
        try {
            return TtsTemplateSpecifications.hasStatus(parseEnum(statusStr, TtsTemplateStatus.values()));
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
        return new ResourceNotFoundException("TTS template not found");
    }
}
