package com.shivang.obd.authz;

import com.shivang.obd.common.exception.ForbiddenException;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central authorization authority. Evaluates authenticated user →
 * organizational memberships → roles → capabilities → scope → target
 * context. PLATFORM / RESELLER / TENANT scopes are fully resolved here.
 * OWN and ASSIGNED are delegated to {@link ResourceAuthorizationPolicy}.
 *
 * <p>Authorization evaluation order:
 * 1. authenticated user
 * 2. capability (from role_capabilities)
 * 3. organizational scope (PLATFORM / RESELLER / TENANT)
 * 4. tenant/reseller hierarchy resolution
 * 5. resource ownership/assignment policy (when scope is insufficient)
 *
 * <p>Tenant isolation is the strongest boundary: a resource belonging to a
 * different organizational context is always denied regardless of ownership
 * or assignment.</p>
 */
@Service
public class AuthorizationService {

    private final List<RoleAssignmentReader> assignmentReaders;
    private final ObjectProvider<TenantHierarchyResolver> hierarchyResolver;
    private final CapabilityRepository capabilityRepository;
    private final RoleCapabilityRepository roleCapabilityRepository;
    private final List<ResourceAuthorizationPolicy> resourcePolicies;

    public AuthorizationService(
        List<RoleAssignmentReader> assignmentReaders,
        ObjectProvider<TenantHierarchyResolver> hierarchyResolver,
        CapabilityRepository capabilityRepository,
        RoleCapabilityRepository roleCapabilityRepository,
        List<ResourceAuthorizationPolicy> resourcePolicies
    ) {
        this.assignmentReaders = List.copyOf(assignmentReaders);
        this.hierarchyResolver = hierarchyResolver;
        this.capabilityRepository = capabilityRepository;
        this.roleCapabilityRepository = roleCapabilityRepository;
        this.resourcePolicies = resourcePolicies == null ? List.of() : List.copyOf(resourcePolicies);
    }

    /**
     * Scope-based capability check. Returns true only when the user's role
     * has the requested capability AND their organizational scope covers the
     * target context.
     */
    @Transactional(readOnly = true)
    public boolean hasCapability(UUID userId, String capabilityKey, AccessCheck target) {
        if (userId == null || capabilityKey == null) {
            return false;
        }
        UUID capabilityId = findCapabilityId(capabilityKey);
        if (capabilityId == null) {
            return false;
        }
        return collectAssignments(userId).stream()
            .filter(assignment -> covers(assignment, target))
            .anyMatch(assignment -> roleHasCapability(assignment.roleId(), capabilityId));
    }

    /**
     * Resource-aware authorization: first evaluates scope-based access, then
     * delegates to {@link ResourceAuthorizationPolicy} implementations for
     * OWN/ASSIGNED decisions when the scope check alone is insufficient.
     */
    @Transactional(readOnly = true)
    public boolean hasResourceAccess(
        UUID userId, String capabilityKey, String resourceType, UUID resourceId, AccessCheck target
    ) {
        if (userId == null || capabilityKey == null || resourceType == null) {
            return false;
        }

        // Step 1: Scope-based authorization covers PLATFORM, RESELLER, and TENANT.
        if (hasCapability(userId, capabilityKey, target)) {
            return true;
        }

        // Step 2: OWN/ASSIGNED require a ResourceAuthorizationPolicy.
        // The policy must verify both the resource-specific condition (owner,
        // assignee) and that the resource's organizational boundary matches.
        UUID capabilityId = findCapabilityId(capabilityKey);
        if (capabilityId == null) {
            return false;
        }
        return collectAssignments(userId).stream()
            .anyMatch(assignment ->
                assignment.scope() == Scope.OWN || assignment.scope() == Scope.ASSIGNED)
            && resourcePolicies.stream()
                .filter(policy -> policy.supports(resourceType))
                .anyMatch(policy -> policy.isAllowed(userId, capabilityKey, resourceType, resourceId));
    }

    /**
     * Fail-closed authorization gate. Throws ForbiddenException (403
     * ProblemDetail with generic non-leaking message).
     */
    @Transactional(readOnly = true)
    public void requireCapability(UUID userId, String capabilityKey, AccessCheck target) {
        if (!hasCapability(userId, capabilityKey, target)) {
            throw new ForbiddenException();
        }
    }

    /** Fail-closed resource-aware gate including OWN/ASSIGNED policy delegation. */
    @Transactional(readOnly = true)
    public void requireResourceAccess(
        UUID userId, String capabilityKey, String resourceType, UUID resourceId, AccessCheck target
    ) {
        if (!hasResourceAccess(userId, capabilityKey, resourceType, resourceId, target)) {
            throw new ForbiddenException();
        }
    }

    private UUID findCapabilityId(String capabilityKey) {
        return capabilityRepository.findByKeyIgnoreCaseAndActiveTrue(capabilityKey)
            .map(CapabilityEntity::getId)
            .orElse(null);
    }

    private List<RoleAssignmentReader.Assignment> collectAssignments(UUID userId) {
        return assignmentReaders.stream()
            .map(reader -> reader.assignmentsFor(userId))
            .flatMap(Collection::stream)
            .toList();
    }

    private boolean covers(RoleAssignmentReader.Assignment assignment, AccessCheck target) {
        AccessCheck check = target == null ? AccessCheck.platformWide() : target;
        return switch (assignment.scope()) {
            case PLATFORM -> true;
            case RESELLER -> coversReseller(assignment, check);
            case TENANT -> check.tenantId() != null && check.tenantId().equals(assignment.tenantId());
            case OWN, ASSIGNED -> false;
        };
    }

    private boolean coversReseller(RoleAssignmentReader.Assignment assignment, AccessCheck check) {
        if (assignment.resellerId() == null) {
            return false;
        }
        if (check.resellerId() != null) {
            return check.resellerId().equals(assignment.resellerId());
        }
        if (check.tenantId() == null) {
            return false;
        }
        TenantHierarchyResolver resolver = hierarchyResolver.getIfAvailable();
        if (resolver == null) {
            return false;
        }
        Optional<UUID> owningReseller = resolver.resellerIdOf(check.tenantId());
        return owningReseller.isPresent() && owningReseller.get().equals(assignment.resellerId());
    }

    private boolean roleHasCapability(UUID roleId, UUID capabilityId) {
        return roleCapabilityRepository.existsByRoleIdAndCapabilityId(roleId, capabilityId);
    }

    /**
     * Returns all capability keys the user has across all their roles and scopes.
     * This is used for frontend authorization UX (navigation visibility, action visibility).
     * Does not apply scope filtering - returns the union of all capabilities.
     */
    @Transactional(readOnly = true)
    public Set<String> getAllCapabilitiesForUser(UUID userId) {
        if (userId == null) {
            return Set.of();
        }
        return collectAssignments(userId).stream()
            .flatMap(assignment -> {
                UUID capabilityId = null;
                // We need to get all capabilities for this role
                return roleCapabilityRepository.findCapabilityKeysByRoleId(assignment.roleId()).stream();
            })
            .collect(Collectors.toSet());
    }
}
