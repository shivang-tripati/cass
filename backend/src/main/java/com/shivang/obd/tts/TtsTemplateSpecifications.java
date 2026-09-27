package com.shivang.obd.tts;

import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Reusable TTS template query predicates. Every list path must compose
 * {@link #notDeleted()} with a boundary specification derived from the
 * caller's server-side organizational scope.
 */
public final class TtsTemplateSpecifications {

    private TtsTemplateSpecifications() {
    }

    public static Specification<TtsTemplateEntity> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<TtsTemplateEntity> forTenant(UUID tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    public static Specification<TtsTemplateEntity> forTenants(java.util.Collection<UUID> tenantIds) {
        return (root, query, cb) -> root.get("tenantId").in(tenantIds);
    }

    public static Specification<TtsTemplateEntity> hasStatus(TtsTemplateStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /**
     * Management-list visibility for one tenant (V42): the tenant's own
     * TENANT rows plus APPROVED, non-deleted GLOBAL rows (the shared
     * catalog they can actually use). Never exposes other tenants' rows;
     * deliberately not equivalent to campaign usability (own unapproved
     * rows remain manageable here).
     */
    public static Specification<TtsTemplateEntity> visibleToTenant(UUID tenantId) {
        return (root, query, cb) -> cb.or(
            cb.equal(root.get("tenantId"), tenantId),
            visibleGlobalCatalogPredicate(root, query, cb));
    }

    /**
     * Reseller hierarchy list visibility (V42): TENANT rows of the
     * hierarchy tenants plus the APPROVED GLOBAL catalog (read-only for
     * resellers; mutation stays platform-only). Never exposes rows of
     * tenants outside the hierarchy.
     */
    public static Specification<TtsTemplateEntity> visibleToTenants(java.util.Collection<UUID> tenantIds) {
        return (root, query, cb) -> cb.or(
            root.get("tenantId").in(tenantIds),
            visibleGlobalCatalogPredicate(root, query, cb));
    }

    /** Empty-hierarchy reseller case: only the shared APPROVED GLOBAL catalog. */
    public static Specification<TtsTemplateEntity> visibleGlobalOnly() {
        return (root, query, cb) -> visibleGlobalCatalogPredicate(root, query, cb);
    }

    private static jakarta.persistence.criteria.Predicate visibleGlobalCatalogPredicate(
        jakarta.persistence.criteria.Root<TtsTemplateEntity> root,
        jakarta.persistence.criteria.CriteriaQuery<?> query,
        jakarta.persistence.criteria.CriteriaBuilder cb
    ) {
        return cb.and(
            cb.equal(root.get("scope"), TtsTemplateScope.GLOBAL),
            cb.equal(root.get("status"), TtsTemplateStatus.APPROVED));
    }

    /** Case-insensitive containment search over name and template text. */
    public static Specification<TtsTemplateEntity> search(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
            cb.like(cb.lower(root.get("name")), pattern),
            cb.like(cb.lower(root.get("templateText")), pattern));
    }

    /** Composes all non-null specifications with AND. */
    @SafeVarargs
    public static Specification<TtsTemplateEntity> compose(Specification<TtsTemplateEntity>... specs) {
        Specification<TtsTemplateEntity> result = null;
        for (Specification<TtsTemplateEntity> spec : specs) {
            if (spec != null) {
                result = result == null ? spec : result.and(spec);
            }
        }
        return result != null ? result : (root, query, cb) -> cb.conjunction();
    }
}
