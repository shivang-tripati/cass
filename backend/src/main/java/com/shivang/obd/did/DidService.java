package com.shivang.obd.did;

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
import com.shivang.obd.did.dto.AssignDidRequest;
import com.shivang.obd.did.dto.AssignDidResponse;
import com.shivang.obd.did.dto.CreateDidRequest;
import com.shivang.obd.did.dto.DidResponse;
import com.shivang.obd.did.dto.UpdateDidRequest;
import com.shivang.obd.reseller.ResellerEntity;
import com.shivang.obd.reseller.ResellerRepository;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DID application service. Authorization is enforced at this boundary
 * from the server-derived organizational context; client-supplied
 * tenant/reseller identifiers are honored only inside the caller's
 * scope. Tenant-scoped lookups constrain the query itself so foreign
 * and nonexistent DIDs are indistinguishable (404).
 */
@Service
@RequiredArgsConstructor
public class DidService {

    private static final String CAP_VIEW = "DID_VIEW";
    private static final String CAP_MANAGE = "DID_MANAGE";

    private static final List<String> SORTABLE_FIELDS =
        List.of("e164Number", "createdAt", "updatedAt", "status", "allocationState",
            "numberType", "provider");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final DidRepository repository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final DidMapper mapper;
    private final TenantRepository tenantRepository;
    private final ResellerRepository resellerRepository;

    @Transactional
    public ApiResponse<DidResponse> create(CreateDidRequest request) {
        UUID userId = requireUserId();
        Scope scope = currentScope();

        UUID tenantId;
        UUID resellerId;
        if (scope.tenantId() != null) {
            // Tenant callers always register under their own tenant; the
            // associated reseller is derived from persisted tenant state.
            tenantId = scope.tenantId();
            resellerId = requireTenantUsable(tenantId).getResellerId();
        } else if (scope.resellerId() != null) {
            UUID requestedTenantId = request.tenantId();
            if (requestedTenantId != null) {
                TenantEntity target = requireTenantUsable(requestedTenantId);
                if (!scope.resellerId().equals(target.getResellerId())) {
                    throw business("Referenced tenant does not exist or is outside your managed hierarchy.");
                }
                resellerId = scope.resellerId();
                tenantId = requestedTenantId;
            } else {
                // Pool number held under the caller's reseller.
                resellerId = scope.resellerId();
                tenantId = null;
            }
        } else {
            // Platform callers may register platform-pool numbers or
            // allocate into any validated organization.
            UUID requestedTenantId = request.tenantId();
            if (requestedTenantId != null) {
                TenantEntity target = requireTenantUsable(requestedTenantId);
                tenantId = target.getId();
                resellerId = target.getResellerId();
            } else {
                UUID requestedResellerId = request.resellerId();
                resellerId = requestedResellerId == null ? null : requireResellerUsable(requestedResellerId);
                tenantId = null;
            }
        }

        AccessCheck boundary = boundaryFor(tenantId, resellerId);
        authorizationService.requireCapability(userId, CAP_MANAGE, boundary);

        validateAllocationConsistency(request.allocationState(), tenantId);
        if (repository.existsByE164NumberAndDeletedAtIsNull(normalizeE164(request.e164Number()))) {
            throw new ConflictException("A DID with this E.164 number already exists.");
        }

        DidEntity entity = mapper.toEntity(request);
        entity.setE164Number(normalizeE164(entity.getE164Number()));
        entity.setTenantId(tenantId);
        entity.setResellerId(resellerId);
        DidEntity saved = repository.save(entity);
        return ResponseFactory.created(mapper.toResponse(saved));
    }

    @Transactional(readOnly = true)
    public ApiResponse<DidResponse> getById(UUID didId) {
        UUID userId = requireUserId();
        DidEntity entity = findVisible(didId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_VIEW, boundaryFor(entity.getTenantId(), entity.getResellerId()));
        return ResponseFactory.ok(mapper.toResponse(entity));
    }

    /**
     * Paginated listing implicitly scoped to the caller's organizational
     * context: own tenant / own reseller pool and managed tenants /
     * platform-wide. Ownership filters are honored only for scopes that
     * span multiple organizations.
     */
    @Transactional(readOnly = true)
    public ApiResponse<List<DidResponse>> list(
        int page, int size, String[] sortArr, String statusFilter, String allocationStateFilter,
        String numberTypeFilter, String providerFilter, String circleFilter,
        UUID tenantFilter, UUID resellerFilter, String search
    ) {
        UUID userId = requireUserId();
        Scope scope = currentScope();

        Specification<DidEntity> boundary;
        if (scope.tenantId() != null) {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.forTenant(scope.tenantId()));
            boundary = DidSpecifications.forTenant(scope.tenantId());
        } else if (scope.resellerId() != null) {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.forReseller(scope.resellerId()));
            List<UUID> hierarchyTenants = hierarchyTenantIds(scope.resellerId());
            boundary = DidSpecifications.ownedByResellerOrTenants(scope.resellerId(), hierarchyTenants);
        } else {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.platformWide());
            Specification<DidEntity> ownership = null;
            if (tenantFilter != null) {
                ownership = DidSpecifications.forTenant(tenantFilter);
            } else if (resellerFilter != null) {
                ownership = DidSpecifications.ownedByResellerOrTenants(resellerFilter, hierarchyTenantIds(resellerFilter));
            }
            boundary = ownership;
        }

        Specification<DidEntity> specification = DidSpecifications.compose(
            DidSpecifications.notDeleted(),
            boundary,
            enumFilter(statusFilter, DidStatus.values(), "status", DidSpecifications::hasStatus),
            enumFilter(allocationStateFilter, AllocationState.values(), "allocationState", DidSpecifications::hasAllocationState),
            enumFilter(numberTypeFilter, NumberType.values(), "numberType", DidSpecifications::hasNumberType),
            textEqualsFilter(providerFilter, DidSpecifications::hasProvider),
            textEqualsFilter(circleFilter, DidSpecifications::hasCircle),
            DidSpecifications.search(search));

        Page<DidEntity> resultPage = repository.findAll(specification, buildPageable(page, size, sortArr));
        List<DidResponse> items = resultPage.getContent().stream().map(mapper::toResponse).toList();
        return ResponseFactory.page(items, PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    @Transactional
    public ApiResponse<DidResponse> update(UUID didId, UpdateDidRequest request) {
        UUID userId = requireUserId();
        DidEntity entity = findVisible(didId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_MANAGE, boundaryFor(entity.getTenantId(), entity.getResellerId()));

        validateAllocationConsistency(request.allocationState(), entity.getTenantId());
        mapper.updateEntity(entity, request);
        DidEntity saved = repository.save(entity);
        return ResponseFactory.ok(mapper.toResponse(saved));
    }

    /** Soft delete: preserves the row, stamps both deletion audit columns. */
    @Transactional
    public void delete(UUID didId) {
        UUID userId = requireUserId();
        DidEntity entity = findVisible(didId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_MANAGE, boundaryFor(entity.getTenantId(), entity.getResellerId()));

        Instant now = Instant.now();
        entity.setDeletedAt(now);
        entity.setDeletedBy(userId.toString());
        repository.save(entity);
    }

    // === VB-5C: DID assignment / revocation lifecycle ===

    /**
     * Assigns a DID to a target organization. Platform callers may assign
     * any AVAILABLE platform-pool DID to a reseller pool or directly to a
     * tenant; reseller callers may assign only their own AVAILABLE
     * reseller-pool DIDs to their own active tenants. Tenants have no
     * assignment authority. The transition itself is a PostgreSQL
     * conditional UPDATE, so concurrent assignments of the same DID yield
     * exactly one winner.
     */
    @Transactional
    public ApiResponse<AssignDidResponse> assign(UUID didId, AssignDidRequest request) {
        UUID userId = requireUserId();
        Scope scope = currentScope();
        if (request == null || request.targetId() == null) {
            throw business("Assignment target must be specified.");
        }

        DidEntity did = findVisible(didId, scope);
        if (did.getStatus() != DidStatus.ACTIVE) {
            throw business("An operationally inactive DID cannot be assigned.");
        }

        if (scope.tenantId() != null) {
            throw business("Tenants cannot assign or transfer DID inventory.");
        }

        UUID resellerId = scope.resellerId();
        if (resellerId != null) {
            return assignResellerDidToTenant(userId, did, resellerId, request.targetId());
        }
        return assignPlatformDid(userId, did, request.targetId());
    }

    /**
     * Revokes a live assignment and restores the DID to its original
     * allocation pool (provenance). Platform callers may revoke any live
     * assignment; reseller callers may revoke assignments of DIDs owned by
     * their pool or held by their tenants; tenants cannot revoke.
     */
    @Transactional
    public ApiResponse<AssignDidResponse> revoke(UUID didId) {
        UUID userId = requireUserId();
        Scope scope = currentScope();

        DidEntity did = findVisible(didId, scope);
        if (did.getStatus() != DidStatus.ACTIVE) {
            throw business("An operationally inactive DID cannot be revoked.");
        }
        if (did.getTenantId() == null && did.getResellerId() == null) {
            throw new ConflictException("DID is not assigned.");
        }
        if (scope.tenantId() != null) {
            throw business("Tenants cannot revoke DID assignments.");
        }

        UUID resellerId = scope.resellerId();
        if (resellerId != null) {
            requireCapabilityForManagedDid(userId, did, resellerId);
        } else {
            authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.platformWide());
        }

        int updated;
        if (did.getTenantId() != null) {
            updated = repository.revokeFromTenant(did.getId(), did.getTenantId());
        } else {
            updated = repository.revokeFromReseller(did.getId(), did.getResellerId());
        }
        if (updated != 1) {
            // Lost a concurrent revoke/reassign race; the current state is
            // authoritative and did not match the revocation precondition.
            throw new ConflictException("DID is not assigned.");
        }
        DidEntity revoked = repository.findById(did.getId()).orElseThrow();
        return ResponseFactory.ok(toAllocationResponse(revoked));
    }

    // === allocation internals ===

    /** Platform-scope assignment: choose reseller pool or direct tenant. */
    private ApiResponse<AssignDidResponse> assignPlatformDid(
        UUID userId, DidEntity did, UUID targetId
    ) {
        if (did.getTenantId() != null || did.getResellerId() != null) {
            throw new ConflictException("DID is already assigned and must be revoked first.");
        }
        authorizationService.requireCapability(
            userId, CAP_MANAGE, AccessCheck.platformWide());

        // Target semantics: try reseller first, then tenant (ids are
        // non-overlapping UUID namespaces of different tables).
        UUID resellerId = resellerRepository.findByIdAndDeletedAtIsNull(targetId)
            .filter(r -> r.getStatus() == LifecycleStatus.ACTIVE)
            .map(ResellerEntity::getId)
            .orElse(null);
        if (resellerId != null) {
            int updated = repository.assignFromPlatformPoolToReseller(did.getId(), resellerId);
            requireWon(updated, "DID is not available for assignment.");
            DidEntity assigned = repository.findById(did.getId()).orElseThrow();
            return ResponseFactory.ok(toAllocationResponse(assigned));
        }
        UUID tenantId = tenantRepository.findByIdAndDeletedAtIsNull(targetId)
            .filter(t -> t.getStatus() == LifecycleStatus.ACTIVE)
            .map(TenantEntity::getId)
            .orElseThrow(() -> business("Assignment target does not exist or is not usable."));
        int updated = repository.assignFromPlatformPoolToTenant(did.getId(), tenantId);
        requireWon(updated, "DID is not available for assignment.");
        DidEntity assigned = repository.findById(did.getId()).orElseThrow();
        return ResponseFactory.ok(toAllocationResponse(assigned));
    }

    /** Reseller-scope assignment: own AVAILABLE pool DID → own active tenant. */
    private ApiResponse<AssignDidResponse> assignResellerDidToTenant(
        UUID userId, DidEntity did, UUID resellerId, UUID targetTenantId
    ) {
        if (did.getTenantId() != null) {
            throw new ConflictException("DID is already assigned and must be revoked first.");
        }
        if (!resellerId.equals(did.getResellerId())) {
            throw business("DID does not belong to your reseller pool.");
        }
        authorizationService.requireCapability(
            userId, CAP_MANAGE, AccessCheck.forReseller(resellerId));
        UUID tenantId = tenantRepository.findByIdAndDeletedAtIsNull(targetTenantId)
            .filter(t -> resellerId.equals(t.getResellerId()))
            .filter(t -> t.getStatus() == LifecycleStatus.ACTIVE)
            .map(TenantEntity::getId)
            .orElseThrow(() -> business(
                "Target tenant does not exist in your managed hierarchy or is not usable."));

        int updated = repository.assignFromResellerPoolToTenant(did.getId(), resellerId, tenantId);
        requireWon(updated, "DID is not available for assignment.");
        DidEntity assigned = repository.findById(did.getId()).orElseThrow();
        return ResponseFactory.ok(toAllocationResponse(assigned));
    }

    /** Resellers may revoke only within their own pool/tenant hierarchy. */
    private void requireCapabilityForManagedDid(UUID userId, DidEntity did, UUID resellerId) {
        boolean inHierarchy =
            (did.getTenantId() != null && hierarchyTenantIds(resellerId).contains(did.getTenantId()))
                || resellerId.equals(did.getResellerId());
        if (!inHierarchy) {
            throw notFound();
        }
        authorizationService.requireCapability(
            userId, CAP_MANAGE, AccessCheck.forReseller(resellerId));
    }

    private void requireWon(int updatedRows, String message) {
        if (updatedRows != 1) {
            throw new ConflictException(message);
        }
    }

    private AssignDidResponse toAllocationResponse(DidEntity entity) {
        return new AssignDidResponse(
            entity.getId(),
            entity.getAllocationState(),
            entity.getAllocationSource(),
            entity.getTenantId(),
            entity.getResellerId()
        );
    }

    // === internal ===

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

    private static AccessCheck boundaryFor(UUID tenantId, UUID resellerId) {
        if (tenantId != null) {
            return AccessCheck.forTenant(tenantId);
        }
        if (resellerId != null) {
            return AccessCheck.forReseller(resellerId);
        }
        return AccessCheck.platformWide();
    }

    /**
     * Boundary-constrained lookup. TENANT callers use a query-level
     * filter; RESELLER/PLATFORM callers load undeleted rows and resolve
     * visibility before any capability decision — every out-of-scope
     * outcome is the same 404, so no existence oracle exists.
     */
    private DidEntity findVisible(UUID didId, Scope scope) {
        if (scope.tenantId() != null) {
            return repository.findByIdAndTenantIdAndDeletedAtIsNull(didId, scope.tenantId())
                .orElseThrow(DidService::notFound);
        }
        DidEntity entity = repository.findByIdAndDeletedAtIsNull(didId)
            .orElseThrow(DidService::notFound);
        if (scope.resellerId() != null && !visibleToReseller(entity, scope.resellerId())) {
            throw notFound();
        }
        return entity;
    }

    /** Resellers see their pool numbers plus numbers assigned to their active tenants. */
    private boolean visibleToReseller(DidEntity entity, UUID resellerId) {
        if (resellerId.equals(entity.getResellerId())) {
            return true;
        }
        return entity.getTenantId() != null && hierarchyTenantIds(resellerId).contains(entity.getTenantId());
    }

    private List<UUID> hierarchyTenantIds(UUID resellerId) {
        return tenantRepository.findAllByResellerIdAndStatus(resellerId, LifecycleStatus.ACTIVE)
            .stream()
            .map(TenantEntity::getId)
            .toList();
    }

    /** Existence + lifecycle check that never distinguishes missing from foreign. */
    private TenantEntity requireTenantUsable(UUID tenantId) {
        TenantEntity tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
            .orElseThrow(() -> business("Referenced tenant does not exist or is not usable."));
        if (tenant.getStatus() != LifecycleStatus.ACTIVE) {
            throw business("Referenced tenant does not exist or is not usable.");
        }
        return tenant;
    }

    private UUID requireResellerUsable(UUID resellerId) {
        ResellerEntity reseller = resellerRepository.findByIdAndDeletedAtIsNull(resellerId)
            .orElseThrow(() -> business("Referenced reseller does not exist or is not usable."));
        if (reseller.getStatus() != LifecycleStatus.ACTIVE) {
            throw business("Referenced reseller does not exist or is not usable.");
        }
        return reseller.getId();
    }

    private void validateAllocationConsistency(AllocationState allocationState, UUID tenantId) {
        if (allocationState == AllocationState.ASSIGNED && tenantId == null) {
            throw business("An ASSIGNED DID must belong to a tenant.");
        }
    }

    private String normalizeE164(String e164Number) {
        return e164Number.trim();
    }

    // === filters, sorting, actor ===

    private <E extends Enum<E>> Specification<DidEntity> enumFilter(
        String rawValue, E[] values, String label,
        Function<E, Specification<DidEntity>> specificationFactory
    ) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            E parsed = parseEnum(rawValue, values);
            return specificationFactory.apply(parsed);
        } catch (IllegalArgumentException ex) {
            throw business("Unknown " + label + " filter: " + rawValue);
        }
    }

    private Specification<DidEntity> textEqualsFilter(
        String rawValue, Function<String, Specification<DidEntity>> specificationFactory
    ) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        return specificationFactory.apply(rawValue.trim());
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

    /** Allowlisted sort fields; unknown fields fall back to the documented default. */
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
        return new ResourceNotFoundException("DID not found");
    }
}
