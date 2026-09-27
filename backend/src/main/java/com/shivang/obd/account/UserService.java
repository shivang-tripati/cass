package com.shivang.obd.account;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.authz.home.OrganizationalHomeEntity;
import com.shivang.obd.authz.home.OrganizationalHomeRepository;
import com.shivang.obd.authz.home.OrganizationalHomeType;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.PaginationMetadata;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.identity.UserEntity;
import com.shivang.obd.identity.UserRepository;
import com.shivang.obd.account.dto.UpdateUserRequest;
import com.shivang.obd.account.dto.UserResponse;
import com.shivang.obd.security.CurrentUserProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User management within the caller's server-derived organizational
 * boundary. The authorization target is derived from the TARGET user's
 * organizational home, so cross-tenant/cross-reseller reads and updates
 * fail closed without leaking existence.
 */
@Service
public class UserService {

    private static final String CAP_VIEW = "USER_VIEW";
    private static final String CAP_MANAGE = "USER_MANAGE";
    private static final List<String> SORTABLE_FIELDS =
        List.of("email", "displayName", "createdAt", "updatedAt", "status");

    private final UserRepository userRepository;
    private final OrganizationalHomeRepository homeRepository;
    private final com.shivang.obd.tenant.TenantRepository tenantRepository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;

    public UserService(
        UserRepository userRepository,
        OrganizationalHomeRepository homeRepository,
        com.shivang.obd.tenant.TenantRepository tenantRepository,
        AuthorizationService authorizationService,
        CurrentUserProvider currentUserProvider
    ) {
        this.userRepository = userRepository;
        this.homeRepository = homeRepository;
        this.tenantRepository = tenantRepository;
        this.authorizationService = authorizationService;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional(readOnly = true)
    public ApiResponse<UserResponse> getById(UUID userId) {
        var target = findUndeleted(userId);
        authorizationService.requireCapability(
            requireUserId(), CAP_VIEW, accessCheckForTarget(target));
        return ResponseFactory.ok(toResponse(target));
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<UserResponse>> list(
        int page, int size, String[] sortArr, String statusFilter, String search
    ) {
        var callerId = requireUserId();
        Specification<UserEntity> spec = UserSpecifications.notDeleted();

        var context = OrganizationContextHolder.current().orElse(null);
        List<UUID> visibleIds = null;
        if (context != null && context.tenantId() != null) {
            authorizationService.requireCapability(
                callerId, CAP_VIEW, AccessCheck.forTenant(context.tenantId()));
            visibleIds = userIdsOfHomes(
                OrganizationalHomeType.TENANT, List.of(context.tenantId()));
        } else if (context != null && context.resellerId() != null) {
            authorizationService.requireCapability(
                callerId, CAP_VIEW, AccessCheck.forReseller(context.resellerId()));
            visibleIds = resellerHierarchyUserIds(context.resellerId());
        } else {
            authorizationService.requireCapability(callerId, CAP_VIEW, AccessCheck.platformWide());
        }

        if (visibleIds != null) {
            if (visibleIds.isEmpty()) {
                return ResponseFactory.page(List.of(), PaginationMetadata.of(page, size, 0));
            }
            spec = spec.and(UserSpecifications.hasIdIn(visibleIds));
        }

        if (statusFilter != null && !statusFilter.isBlank()) {
            spec = spec.and(UserSpecifications.hasStatus(parseStatus(statusFilter)));
        }
        if (search != null && !search.isBlank()) {
            spec = spec.and(UserSpecifications.search(search));
        }

        Page<UserEntity> resultPage =
            userRepository.findAll(spec, buildPageable(page, size, sortArr));
        List<UserResponse> items = resultPage.getContent().stream()
            .map(this::toResponse).toList();
        return ResponseFactory.page(
            items, PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    /**
     * Profile/status update. Capability is checked against the TARGET's
     * organizational boundary: TENANT_ADMIN manages its own tenant users;
     * RESELLER_ADMIN its hierarchy; SUPER_ADMIN everyone. AGENT (no
     * USER_MANAGE capability) always fails closed.
     */
    @Transactional
    public ApiResponse<UserResponse> update(UUID userId, UpdateUserRequest request) {
        var target = findUndeleted(userId);
        authorizationService.requireCapability(
            requireUserId(), CAP_MANAGE, accessCheckForTarget(target));

        if (request.displayName() != null && !request.displayName().isBlank()) {
            target.setDisplayName(request.displayName().trim());
        }
        if (request.status() != null) {
            target.setStatus(request.status());
        }
        return ResponseFactory.ok(toResponse(userRepository.save(target)));
    }

    // === internal ===

    /** Target-derived boundary: PLATFORM users are platform-wide visible. */
    private AccessCheck accessCheckForTarget(UserEntity target) {
        return homeRepository.findByUserId(target.getId())
            .<AccessCheck>map(home -> home.getHomeType() == OrganizationalHomeType.TENANT
                ? AccessCheck.forTenant(home.getOrganizationId())
                : AccessCheck.forReseller(home.getOrganizationId()))
            .orElseGet(AccessCheck::platformWide);
    }

    private List<UUID> resellerHierarchyUserIds(UUID resellerId) {
        List<UUID> tenantIds = tenantRepository
            .findAllByResellerIdAndStatus(resellerId, LifecycleStatus.ACTIVE)
            .stream().map(com.shivang.obd.tenant.TenantEntity::getId).toList();
        List<UUID> ids = new java.util.ArrayList<>(
            userIdsOfHomes(OrganizationalHomeType.RESELLER, List.of(resellerId)));
        if (!tenantIds.isEmpty()) {
            ids.addAll(userIdsOfHomes(OrganizationalHomeType.TENANT, tenantIds));
        }
        return ids.stream().distinct().toList();
    }

    private List<UUID> userIdsOfHomes(
        OrganizationalHomeType type, java.util.Collection<UUID> organizationIds
    ) {
        return homeRepository.findByHomeTypeAndOrganizationIdIn(type, organizationIds).stream()
            .map(OrganizationalHomeEntity::getUserId)
            .distinct()
            .toList();
    }

    private UUID requireUserId() {
        return currentUserProvider.current()
            .orElseThrow(() -> new BusinessException(
                CommonErrorCode.UNAUTHORIZED, "Authentication required."))
            .userId();
    }

    private UserEntity findUndeleted(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
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

    private UserResponse toResponse(UserEntity user) {
        Optional<OrganizationalHomeEntity> home = homeRepository.findByUserId(user.getId());
        return new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            user.getStatus(),
            home.map(OrganizationalHomeEntity::getHomeType).orElse(null),
            home.map(OrganizationalHomeEntity::getOrganizationId).orElse(null),
            user.getCreatedAt()
        );
    }
}
