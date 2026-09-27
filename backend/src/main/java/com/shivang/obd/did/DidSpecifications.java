package com.shivang.obd.did;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Reusable DID query predicates. All list paths must compose
 * {@link #notDeleted()} with a boundary specification derived from the
 * caller's server-side organizational scope.
 */
public final class DidSpecifications {

    private DidSpecifications() {
    }

    public static Specification<DidEntity> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<DidEntity> hasStatus(DidStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<DidEntity> hasAllocationState(AllocationState state) {
        return (root, query, cb) -> cb.equal(root.get("allocationState"), state);
    }

    public static Specification<DidEntity> hasNumberType(NumberType numberType) {
        return (root, query, cb) -> cb.equal(root.get("numberType"), numberType);
    }

    public static Specification<DidEntity> hasProvider(String provider) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("provider")), provider.toLowerCase());
    }

    public static Specification<DidEntity> hasCircle(String circle) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("circle")), circle.toLowerCase());
    }

    public static Specification<DidEntity> forTenant(UUID tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    public static Specification<DidEntity> forTenants(Collection<UUID> tenantIds) {
        return (root, query, cb) -> root.get("tenantId").in(tenantIds);
    }

    /** Reseller boundary: numbers held in the reseller pool OR assigned to its tenants. */
    public static Specification<DidEntity> ownedByResellerOrTenants(
        UUID resellerId, Collection<UUID> hierarchyTenantIds
    ) {
        return (root, query, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            parts.add(cb.equal(root.get("resellerId"), resellerId));
            if (hierarchyTenantIds != null && !hierarchyTenantIds.isEmpty()) {
                parts.add(root.get("tenantId").in(hierarchyTenantIds));
            }
            return cb.or(parts.toArray(new Predicate[0]));
        };
    }

    /** Case-insensitive containment search over E.164 number, circle and provider. */
    public static Specification<DidEntity> search(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
            cb.like(cb.lower(root.get("e164Number")), pattern),
            cb.like(cb.lower(cb.coalesce(root.get("circle"), "")), pattern),
            cb.like(cb.lower(root.get("provider")), pattern));
    }

    /** Composes all non-null specifications with AND. */
    @SafeVarargs
    public static Specification<DidEntity> compose(Specification<DidEntity>... specs) {
        Specification<DidEntity> result = null;
        for (Specification<DidEntity> spec : specs) {
            if (spec != null) {
                result = result == null ? spec : result.and(spec);
            }
        }
        return result != null ? result : (root, query, cb) -> cb.conjunction();
    }
}
