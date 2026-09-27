package com.shivang.obd.contact;

import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.UUID;

/**
 * Reusable contact-group query predicates. Every list path must compose
 * {@link #notDeleted()} with a boundary specification derived from the
 * caller's server-side organizational scope.
 */
public final class ContactGroupSpecifications {

    private ContactGroupSpecifications() {
    }

    public static Specification<ContactGroupEntity> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<ContactGroupEntity> forTenant(UUID tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    public static Specification<ContactGroupEntity> forTenants(java.util.Collection<UUID> tenantIds) {
        return (root, query, cb) -> root.get("tenantId").in(tenantIds);
    }

    /** Case-insensitive containment search over name and description. */
    public static Specification<ContactGroupEntity> search(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
            cb.like(cb.lower(root.get("name")), pattern),
            cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern));
    }

    /** Composes all non-null specifications with AND. */
    @SafeVarargs
    public static Specification<ContactGroupEntity> compose(Specification<ContactGroupEntity>... specs) {
        Specification<ContactGroupEntity> result = null;
        for (Specification<ContactGroupEntity> spec : specs) {
            if (spec != null) {
                result = result == null ? spec : result.and(spec);
            }
        }
        return result != null ? result : (root, query, cb) -> cb.conjunction();
    }
}
