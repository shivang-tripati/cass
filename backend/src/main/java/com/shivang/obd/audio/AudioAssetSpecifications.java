package com.shivang.obd.audio;

import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Reusable audio-asset query predicates. Every list path must compose
 * {@link #notDeleted()} with a boundary specification derived from the
 * caller's server-side organizational scope.
 */
public final class AudioAssetSpecifications {

    private AudioAssetSpecifications() {
    }

    public static Specification<AudioAssetEntity> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<AudioAssetEntity> forTenant(UUID tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    public static Specification<AudioAssetEntity> forTenants(java.util.Collection<UUID> tenantIds) {
        return (root, query, cb) -> root.get("tenantId").in(tenantIds);
    }

    public static Specification<AudioAssetEntity> hasStatus(AudioAssetStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** Case-insensitive containment search over name and file name. */
    public static Specification<AudioAssetEntity> search(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
            cb.like(cb.lower(root.get("name")), pattern),
            cb.like(cb.lower(root.get("fileName")), pattern));
    }

    /** Composes all non-null specifications with AND. */
    @SafeVarargs
    public static Specification<AudioAssetEntity> compose(Specification<AudioAssetEntity>... specs) {
        Specification<AudioAssetEntity> result = null;
        for (Specification<AudioAssetEntity> spec : specs) {
            if (spec != null) {
                result = result == null ? spec : result.and(spec);
            }
        }
        return result != null ? result : (root, query, cb) -> cb.conjunction();
    }
}
