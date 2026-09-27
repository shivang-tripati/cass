package com.shivang.obd.account;

import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.identity.UserEntity;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class UserSpecifications {

    private UserSpecifications() {
    }

    public static Specification<UserEntity> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<UserEntity> hasIdIn(List<UUID> ids) {
        return (root, query, cb) -> root.get("id").in(ids);
    }

    public static Specification<UserEntity> hasStatus(LifecycleStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<UserEntity> search(String term) {
        String like = "%" + term.toLowerCase() + "%";
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.like(cb.lower(root.get("email")), like));
            predicates.add(cb.like(cb.lower(cb.coalesce(root.get("displayName"), "")), like));
            return cb.or(predicates.toArray(new Predicate[0]));
        };
    }
}
