package com.shivang.obd.tenant;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.PaginationMetadata;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.dto.CreateAgentRequest;
import com.shivang.obd.tenant.dto.CreateTenantRequest;
import com.shivang.obd.tenant.dto.TenantResponse;
import com.shivang.obd.tenant.dto.UpdateTenantRequest;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant CRUD with server-derived, service-boundary authorization.
 * RESELLER_ADMIN scope always comes from the caller's organizational
 * home; TENANT_ADMIN is restricted to its own tenant; client-supplied
 * identifiers are never trusted for authorization decisions.
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private static final String CAP_VIEW = "TENANT_VIEW";
    private static final String CAP_MANAGE = "TENANT_MANAGE";
    private static final List<String> SORTABLE_FIELDS =
        List.of("name", "slug", "createdAt", "updatedAt", "status");

    private final TenantRepository repository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final TenantProvisioningService provisioningService;
    private final TenantMapper mapper;


    /**
     * Provision tenant + TENANT_ADMIN. Dispatches by the caller's
     * server-derived context: reseller-bound callers are locked to their
     * own hierarchy; only platform-scope callers may create direct tenants
     * or choose a target reseller.
     */
    @Transactional
    public ApiResponse<TenantResponse> create(CreateTenantRequest request) {
        var userId = requireUserId();
        var context = OrganizationContextHolder.current().orElse(null);
        if (context != null && context.resellerId() != null) {
            authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forReseller(context.resellerId()));
            return provisioningService.provisionUnderCallerReseller(
                context.resellerId(), request);
        }
        authorizationService.requireCapability(userId, CAP_MANAGE, AccessCheck.platformWide());
        return provisioningService.provisionForSuperAdmin(request);
    }

    /**
     * AGENT provisioning is restricted to SUPER_ADMIN by current functional
     * requirement. The platform-wide target excludes RESELLER/TENANT-scoped
     * assignments, so reseller/tenant admins fail closed here even though
     * their roles also carry USER_MANAGE.
     */
    @Transactional
    public ApiResponse<TenantResponse> createAgent(UUID tenantId, CreateAgentRequest request) {
        authorizationService.requireCapability(
            requireUserId(), "USER_MANAGE", AccessCheck.platformWide());
        return provisioningService.createAgent(tenantId, request);
    }

    @Transactional(readOnly = true)
    public ApiResponse<TenantResponse> getById(UUID tenantId) {
        var entity = findUndeleted(tenantId);
        authorizationService.requireCapability(
            requireUserId(), CAP_VIEW, accessCheckFor(entity));
        return ResponseFactory.ok(mapper.toResponse(entity));
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<TenantResponse>> list(
        int page, int size, String[] sortArr, String statusFilter, String search
    ) {
        var userId = requireUserId();
        Specification<TenantEntity> spec = TenantSpecifications.notDeleted();

        var context = OrganizationContextHolder.current().orElse(null);
        if (context != null && context.tenantId() != null) {
            authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forTenant(context.tenantId()));
            spec = spec.and(TenantSpecifications.hasId(context.tenantId()));
        } else if (context != null && context.resellerId() != null) {
            authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forReseller(context.resellerId()));
            spec = spec.and(TenantSpecifications.hasReseller(context.resellerId()));
        } else {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.platformWide());
        }

        if (statusFilter != null && !statusFilter.isBlank()) {
            spec = spec.and(TenantSpecifications.hasStatus(parseStatus(statusFilter)));
        }
        if (search != null && !search.isBlank()) {
            spec = spec.and(TenantSpecifications.search(search));
        }

        Page<TenantEntity> resultPage =
            repository.findAll(spec, buildPageable(page, size, sortArr));
        List<TenantResponse> items = resultPage.getContent().stream()
            .map(mapper::toResponse).toList();
        return ResponseFactory.page(
            items, PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    @Transactional
    public ApiResponse<TenantResponse> update(UUID tenantId, UpdateTenantRequest request) {
        var entity = findUndeleted(tenantId);
        authorizationService.requireCapability(
            requireUserId(), CAP_MANAGE, accessCheckFor(entity));

        mapper.updateEntity(entity, request);
        return ResponseFactory.ok(mapper.toResponse(repository.save(entity)));
    }

    /** Soft delete within the caller's managed hierarchy. */
    @Transactional
    public void delete(UUID tenantId) {
        var entity = findUndeleted(tenantId);
        var userId = requireUserId();
        authorizationService.requireCapability(
            userId, CAP_MANAGE, accessCheckFor(entity));

        entity.setDeletedAt(Instant.now());
        currentUserProvider.current()
            .ifPresentOrElse(
                user -> entity.setDeletedBy(user.userId().toString()),
                () -> entity.setDeletedBy(com.shivang.obd.common.audit.AuditorProvider.SYSTEM_ACTOR));
        repository.save(entity);
    }

    // === internal ===

    /**
     * Target-derived check covering BOTH organizational paths of the
     * tenant at once: PLATFORM always covers; RESELLER matches its own
     * hierarchy; TENANT matches exactly this tenant. Direct tenants
     * (reseller_id NULL) are only reachable platform-wide or by their own
     * tenant admin. Existence is never leaked across scopes.
     */
    private AccessCheck accessCheckFor(TenantEntity entity) {
        return new AccessCheck(entity.getResellerId(), entity.getId());
    }

    private UUID requireUserId() {
        return currentUserProvider.current()
            .orElseThrow(() -> new BusinessException(
                CommonErrorCode.UNAUTHORIZED, "Authentication required."))
            .userId();
    }

    private TenantEntity findUndeleted(UUID tenantId) {
        return repository.findByIdAndDeletedAtIsNull(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
    }

    private LifecycleStatus parseStatus(String statusStr) {
        try {
            return LifecycleStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                CommonErrorCode.VALIDATION_ERROR, "Unknown status filter: " + statusStr);
        }
    }

    private Pageable buildPageable(int page, int size, String[] sortArr) {
        Sort springSort = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sortArr != null && sortArr.length > 0 && SORTABLE_FIELDS.contains(sortArr[0])) {
            Sort.Direction dir = sortArr.length > 1 && "asc".equalsIgnoreCase(sortArr[1])
                ? Sort.Direction.ASC : Sort.Direction.DESC;
            springSort = Sort.by(dir, sortArr[0]);
        }
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), springSort);
    }
}
