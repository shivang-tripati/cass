package com.shivang.obd.voice.agent;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Reusable {@link Specification}s for agent directory queries (VB-4A),
 * following the contact-group/audio-asset specification pattern.
 */
public final class AgentSpecifications {

    /**
     * Call-leg statuses that count as "an active agent call" for the
     * derived active-call count and the active-calls query. Canonical
     * source: {@link com.shivang.obd.voice.call.CallLegStatus}.
     */
    public static final List<com.shivang.obd.voice.call.CallLegStatus> ACTIVE_LEG_STATUSES =
            List.of(
                    com.shivang.obd.voice.call.CallLegStatus.INITIATED,
                    com.shivang.obd.voice.call.CallLegStatus.DIALING,
                    com.shivang.obd.voice.call.CallLegStatus.RINGING,
                    com.shivang.obd.voice.call.CallLegStatus.ANSWERED,
                    com.shivang.obd.voice.call.CallLegStatus.BRIDGED);

    /**
     * Call-leg statuses that count as "historical" (terminal) for the
     * call-history query. A leg additionally qualifies only when its
     * session has ended (checked at the service boundary).
     */
    public static final List<com.shivang.obd.voice.call.CallLegStatus> HISTORY_LEG_STATUSES =
            List.of(
                    com.shivang.obd.voice.call.CallLegStatus.COMPLETED,
                    com.shivang.obd.voice.call.CallLegStatus.FAILED,
                    com.shivang.obd.voice.call.CallLegStatus.CANCELLED);

    /** Excludes soft-deleted rows. */
    public static Specification<Agent> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    /** Case-insensitive display-name search. */
    public static Specification<Agent> search(String search) {
        return (root, query, cb) -> {
            if (search == null || search.isBlank()) {
                return cb.conjunction();
            }
            return cb.like(cb.lower(root.get("displayName")),
                    "%" + search.toLowerCase() + "%");
        };
    }

    /** Composes specifications with AND (nulls ignored). */
    public static Specification<Agent> compose(
            Specification<Agent> base, Specification<Agent> boundary,
            Specification<Agent> search) {
        List<Specification<Agent>> parts = new ArrayList<>();
        if (base != null) {
            parts.add(base);
        }
        if (boundary != null) {
            parts.add(boundary);
        }
        if (search != null) {
            parts.add(search);
        }
        Specification<Agent> result = null;
        for (Specification<Agent> part : parts) {
            result = result == null ? part : result.and(part);
        }
        return result == null ? (root, query, cb) -> cb.conjunction() : result;
    }

    private AgentSpecifications() {
    }
}
