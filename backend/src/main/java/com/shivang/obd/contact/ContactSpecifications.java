package com.shivang.obd.contact;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Reusable contact query predicates (VB-6B.1: tenant-level identity). */
public final class ContactSpecifications {

    private ContactSpecifications() {
    }

    /**
     * Live contacts among the given identity ids — the join side of the
     * membership table (the group's audience), preserving the previous
     * "live members of a group" query shape without a group column.
     */
    public static Specification<ContactEntity> liveContactInIds(Collection<UUID> ids) {
        return (root, query, cb) -> cb.and(
            root.get("id").in(ids),
            cb.isNull(root.get("deletedAt")));
    }

    /** Case-insensitive containment search over names and phone number. */
    public static Specification<ContactEntity> search(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
            cb.like(cb.lower(root.get("firstName")), pattern),
            cb.like(cb.lower(cb.coalesce(root.get("lastName"), "")), pattern),
            cb.like(cb.lower(root.get("phoneNumber")), pattern));
    }

    /** Composes all non-null specifications with AND. */
    @SafeVarargs
    public static Specification<ContactEntity> compose(Specification<ContactEntity>... specs) {
        Specification<ContactEntity> result = null;
        for (Specification<ContactEntity> spec : specs) {
            result = spec == null ? result : (result == null ? spec : result.and(spec));
        }
        return result != null ? result : (root, query, cb) -> cb.conjunction();
    }
}
