package com.shivang.obd.reseller;

import com.shivang.obd.common.lifecycle.LifecycleStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class ResellerSpecifications {

    private ResellerSpecifications() {
    }

    public static Specification<ResellerEntity> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<ResellerEntity> hasId(java.util.UUID resellerId) {
        return (root, query, cb) -> cb.equal(root.get("id"), resellerId);
    }

    public static Specification<ResellerEntity> hasStatus(LifecycleStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<ResellerEntity> search(String term) {
        String like = "%" + term.toLowerCase() + "%";
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.like(cb.lower(root.get("name")), like));
            predicates.add(cb.like(cb.lower(root.get("slug")), like));
            predicates.add(cb.like(cb.lower(cb.coalesce(root.get("displayName"), "")), like));
            return cb.or(predicates.toArray(new Predicate[0]));
        };
    }
}
