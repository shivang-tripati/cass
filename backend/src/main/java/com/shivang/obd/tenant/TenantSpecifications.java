package com.shivang.obd.tenant;

import com.shivang.obd.common.lifecycle.LifecycleStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class TenantSpecifications {

    private TenantSpecifications() {
    }

    public static Specification<TenantEntity> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<TenantEntity> hasReseller(UUID resellerId) {
        return (root, query, cb) -> cb.equal(root.get("resellerId"), resellerId);
    }

    public static Specification<TenantEntity> hasId(UUID tenantId) {
        return (root, query, cb) -> cb.equal(root.get("id"), tenantId);
    }

    public static Specification<TenantEntity> hasStatus(LifecycleStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<TenantEntity> search(String term) {
        String like = "%" + term.toLowerCase() + "%";
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.like(cb.lower(root.get("name")), like));
            predicates.add(cb.like(cb.lower(root.get("slug")), like));
            return cb.or(predicates.toArray(new Predicate[0]));
        };
    }
}
