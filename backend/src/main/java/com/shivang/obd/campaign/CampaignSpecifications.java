package com.shivang.obd.campaign;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Reusable campaign query predicates. All list paths must compose
 * {@link #notDeleted()} with a tenant-boundary specification
 * ({@link #forTenant(UUID)} / {@link #forTenants(Collection)}).
 */
public final class CampaignSpecifications {

    private CampaignSpecifications() {
    }

    public static Specification<CampaignEntity> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<CampaignEntity> hasStatus(CampaignStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<CampaignEntity> hasType(CampaignType type) {
        return (root, query, cb) -> cb.equal(root.get("campaignType"), type);
    }

    public static Specification<CampaignEntity> hasRunMode(CampaignRunMode runMode) {
        return (root, query, cb) -> cb.equal(root.get("runMode"), runMode);
    }

    public static Specification<CampaignEntity> forTenant(UUID tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    public static Specification<CampaignEntity> forTenants(Collection<UUID> tenantIds) {
        return (root, query, cb) -> root.get("tenantId").in(tenantIds);
    }

    /** Case-insensitive containment search over name and description. */
    public static Specification<CampaignEntity> search(String search) {
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
    public static Specification<CampaignEntity> compose(Specification<CampaignEntity>... specs) {
        Specification<CampaignEntity> result = null;
        for (Specification<CampaignEntity> spec : specs) {
            if (spec != null) {
                result = result == null ? spec : result.and(spec);
            }
        }
        return result != null ? result : (root, query, cb) -> cb.conjunction();
    }
}
