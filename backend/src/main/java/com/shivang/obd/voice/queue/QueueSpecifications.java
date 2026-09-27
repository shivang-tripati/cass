package com.shivang.obd.voice.queue;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Reusable {@link Specification}s for queue listing (VB-4B), following
 * the VB-4A AgentSpecifications pattern: boundary → composable filters.
 */
public final class QueueSpecifications {

    private QueueSpecifications() {
    }

    /** Excludes soft-deleted queues. */
    public static Specification<Queue> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    /** Case-insensitive name search. */
    public static Specification<Queue> search(String search) {
        return (root, query, cb) -> {
            if (search == null || search.isBlank()) {
                return cb.conjunction();
            }
            return cb.like(cb.lower(root.get("name")),
                    "%" + search.trim().toLowerCase() + "%");
        };
    }

    /** Composes boundary + filters (mirrors AgentSpecifications.compose). */
    public static Specification<Queue> compose(
            Specification<Queue> base, Specification<Queue> boundary,
            Specification<Queue> filter) {
        List<Specification<Queue>> parts = new ArrayList<>();
        if (base != null) {
            parts.add(base);
        }
        if (boundary != null) {
            parts.add(boundary);
        }
        if (filter != null) {
            parts.add(filter);
        }
        if (parts.isEmpty()) {
            return (root, query, cb) -> cb.conjunction();
        }
        Specification<Queue> combined = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            final Specification<Queue> next = parts.get(i);
            combined = combined.and(next);
        }
        return combined;
    }
}
