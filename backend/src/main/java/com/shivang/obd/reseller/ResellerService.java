package com.shivang.obd.reseller;

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
import com.shivang.obd.reseller.dto.CreateResellerRequest;
import com.shivang.obd.reseller.dto.ResellerResponse;
import com.shivang.obd.reseller.dto.UpdateResellerRequest;
import com.shivang.obd.security.CurrentUserProvider;
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
 * Reseller CRUD with service-boundary authorization. All organizational
 * boundaries are server-derived: the RESELLER_ADMIN scope always comes from
 * the authenticated user's own reseller, never from client input.
 */
@Service
public class ResellerService {

    private static final String CAP_VIEW = "RESELLER_VIEW";
    private static final String CAP_MANAGE = "RESELLER_MANAGE";
    private static final List<String> SORTABLE_FIELDS =
        List.of("name", "slug", "createdAt", "updatedAt", "status");

    private final ResellerRepository repository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final ResellerProvisioningService provisioningService;
    private final ResellerMapper mapper;

    public ResellerService(
        ResellerRepository repository,
        AuthorizationService authorizationService,
        CurrentUserProvider currentUserProvider,
        ResellerProvisioningService provisioningService,
        ResellerMapper mapper
    ) {
        this.repository = repository;
        this.authorizationService = authorizationService;
        this.currentUserProvider = currentUserProvider;
        this.provisioningService = provisioningService;
        this.mapper = mapper;
    }

    /** SUPER_ADMIN-only: platform-scope target excludes reseller-scoped admins. */
    @Transactional
    public ApiResponse<ResellerResponse> create(CreateResellerRequest request) {
        authorizationService.requireCapability(
            requireUserId(), CAP_MANAGE, AccessCheck.platformWide());
        return provisioningService.provisionForAuthenticatedAdmin(request);
    }

    @Transactional(readOnly = true)
    public ApiResponse<ResellerResponse> getById(UUID resellerId) {
        var entity = findUndeleted(resellerId);
        authorizationService.requireCapability(
            requireUserId(), CAP_VIEW, AccessCheck.forReseller(resellerId));
        return ResponseFactory.ok(mapper.toResponse(entity));
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<ResellerResponse>> list(
        int page, int size, String[] sortArr, String statusFilter, String search
    ) {
        var userId = requireUserId();
        Specification<ResellerEntity> spec = ResellerSpecifications.notDeleted();

        var context = OrganizationContextHolder.current().orElse(null);
        if (context != null && context.resellerId() != null) {
            authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forReseller(context.resellerId()));
            spec = spec.and(ResellerSpecifications.hasId(context.resellerId()));
        } else {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.platformWide());
        }

        if (statusFilter != null && !statusFilter.isBlank()) {
            spec = spec.and(ResellerSpecifications.hasStatus(parseStatus(statusFilter)));
        }
        if (search != null && !search.isBlank()) {
            spec = spec.and(ResellerSpecifications.search(search));
        }

        Page<ResellerEntity> resultPage =
            repository.findAll(spec, buildPageable(page, size, sortArr));
        List<ResellerResponse> items = resultPage.getContent().stream()
            .map(mapper::toResponse).toList();
        return ResponseFactory.page(
            items, PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    @Transactional
    public ApiResponse<ResellerResponse> update(UUID resellerId, UpdateResellerRequest request) {
        var entity = findUndeleted(resellerId);
        authorizationService.requireCapability(
            requireUserId(), CAP_MANAGE, AccessCheck.forReseller(resellerId));

        mapper.updateEntity(entity, request);
        return ResponseFactory.ok(mapper.toResponse(repository.save(entity)));
    }

    /** Soft delete; lifecycle control stays with SUPER_ADMIN. */
    @Transactional
    public void delete(UUID resellerId) {
        var userId = requireUserId();
        var entity = findUndeleted(resellerId);
        authorizationService.requireCapability(userId, CAP_MANAGE, AccessCheck.platformWide());

        entity.setDeletedAt(Instant.now());
        currentUserProvider.current()
            .ifPresentOrElse(
                user -> entity.setDeletedBy(user.userId().toString()),
                () -> entity.setDeletedBy(com.shivang.obd.common.audit.AuditorProvider.SYSTEM_ACTOR));
        repository.save(entity);
    }

    // === internal ===

    private UUID requireUserId() {
        return currentUserProvider.current()
            .orElseThrow(() -> new BusinessException(
                CommonErrorCode.UNAUTHORIZED, "Authentication required."))
            .userId();
    }

    private ResellerEntity findUndeleted(UUID resellerId) {
        return repository.findByIdAndDeletedAtIsNull(resellerId)
            .orElseThrow(() -> new ResourceNotFoundException("Reseller not found"));
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
