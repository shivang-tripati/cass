package com.shivang.obd.contact;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContext;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Single authorization gate for group-scoped operations, shared by
 * {@link ContactGroupService} and {@link ContactGroupMemberService}
 * (VB-6B.2 extraction — a refactor of the existing behavior, not a new
 * authorization model).
 *
 * <p>Visibility is resolved from the server-derived organizational
 * context before any capability decision, so a foreign group and a
 * nonexistent group are indistinguishable (404): TENANT callers get
 * query-level tenant scoping, RESELLER callers must find the group
 * inside their active-hierarchy tenants, PLATFORM callers are
 * unbounded. The capability ({@code CONTACT_VIEW}/{@code CONTACT_MANAGE})
 * is then checked against the owning tenant. Database constraints remain
 * the tenant-isolation authority; this gate never replaces them.</p>
 */
@Component
class ContactGroupAccess {

    private final ContactGroupRepository groupRepository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final TenantRepository tenantRepository;

    ContactGroupAccess(
        ContactGroupRepository groupRepository,
        AuthorizationService authorizationService,
        CurrentUserProvider currentUserProvider,
        TenantRepository tenantRepository
    ) {
        this.groupRepository = groupRepository;
        this.authorizationService = authorizationService;
        this.currentUserProvider = currentUserProvider;
        this.tenantRepository = tenantRepository;
    }

    /** Server-derived caller scope. */
    record Scope(UUID tenantId, UUID resellerId) {

        static Scope of(OrganizationContext ctx) {
            return ctx == null
                ? new Scope(null, null)
                : new Scope(ctx.tenantId(), ctx.resellerId());
        }
    }

    UUID requireUserId() {
        return currentUserProvider.current()
            .orElseThrow(() -> new BusinessException(
                CommonErrorCode.UNAUTHORIZED, "Authentication required."))
            .userId();
    }

    Scope currentScope() {
        return Scope.of(OrganizationContextHolder.current().orElse(null));
    }

    List<UUID> hierarchyTenantIds(UUID resellerId) {
        return tenantRepository.findAllByResellerIdAndStatus(resellerId, LifecycleStatus.ACTIVE)
            .stream()
            .map(TenantEntity::getId)
            .toList();
    }

    /**
     * 404-cloaked visibility resolution (identical semantics to the
     * previous private ContactGroupService implementation): TENANT →
     * tenant-scoped lookup; RESELLER/PLATFORM → live lookup with
     * reseller-hierarchy containment.
     */
    ContactGroupEntity findVisibleGroup(UUID groupId, Scope scope) {
        if (scope.tenantId() != null) {
            return groupRepository.findByIdAndTenantIdAndDeletedAtIsNull(groupId, scope.tenantId())
                .orElseThrow(ContactGroupAccess::groupNotFound);
        }
        ContactGroupEntity entity = groupRepository.findByIdAndDeletedAtIsNull(groupId)
            .orElseThrow(ContactGroupAccess::groupNotFound);
        if (scope.resellerId() != null && !hierarchyTenantIds(scope.resellerId()).contains(entity.getTenantId())) {
            throw groupNotFound();
        }
        return entity;
    }

    /**
     * Single entry point for group-scoped operations: scoped lookup
     * (404-indistinguishable) followed by the capability check against
     * the group's tenant.
     */
    ContactGroupEntity authorizedGroup(UUID groupId, String capabilityKey) {
        UUID userId = requireUserId();
        ContactGroupEntity entity = findVisibleGroup(groupId, currentScope());
        authorizationService.requireCapability(
            userId, capabilityKey, AccessCheck.forTenant(entity.getTenantId()));
        return entity;
    }

    /** Capability check for operations whose target tenant is derived
     *  before any resource lookup (e.g. tenant-scoped group creation). */
    void requireCapability(UUID userId, String capabilityKey, AccessCheck target) {
        authorizationService.requireCapability(userId, capabilityKey, target);
    }

    static ResourceNotFoundException groupNotFound() {
        return new ResourceNotFoundException("Contact group not found");
    }

    static ResourceNotFoundException contactNotFound() {
        return new ResourceNotFoundException("Contact not found");
    }
}
