package com.shivang.obd.voice.call;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CallSessionRepository
        extends JpaRepository<CallSession, UUID>, JpaSpecificationExecutor<CallSession> {

    Optional<CallSession> findByIdAndDeletedAtIsNull(UUID id);

    List<CallSession> findByTenantIdAndDeletedAtIsNull(UUID tenantId);

    Optional<CallSession> findByProviderCallIdAndDeletedAtIsNull(String providerCallId);

    Optional<CallSession> findByCallAttemptIdAndDeletedAtIsNull(UUID callAttemptId);

    @Query("select cs from CallSession cs " +
           "where cs.callAttemptId = :callAttemptId and cs.deletedAt is null")
    Optional<CallSession> findByCallAttemptId(UUID callAttemptId);

    List<CallSession> findByCampaignExecutionIdAndDeletedAtIsNull(UUID campaignExecutionId);

    /**
     * VB-7B: non-terminal sessions whose persisted {@code deadline_at} has passed.
     * Bounded and ordered by the deadline, so the partial index added in V52 makes
     * this a range scan rather than a table sweep.
     *
     * <p>Deliberately campaign-type-neutral: the voice layer must not know which
     * policy stamped a deadline. The caller decides ownership and declines the
     * sessions that are not its own.
     */
    @Query(value = "SELECT s.* FROM call_sessions s "
            + "WHERE s.deleted_at IS NULL "
            + "AND s.deadline_at IS NOT NULL "
            + "AND s.deadline_at <= :now "
            + "AND s.status IN :statuses "
            + "ORDER BY s.deadline_at",
            countQuery = "SELECT count(*) FROM call_sessions s "
                    + "WHERE s.deleted_at IS NULL AND s.deadline_at IS NOT NULL "
                    + "AND s.deadline_at <= :now AND s.status IN :statuses",
            nativeQuery = true)
    List<CallSession> findLiveSessionsWithExpiredDeadline(
            @Param("statuses") List<String> statuses,
            @Param("now") Instant now,
            org.springframework.data.domain.Pageable pageable);

    /**
     * VB-7B: non-terminal, still-unanswered sessions of attempts dispatched at or
     * before {@code cutoff}, with no deadline recorded yet. This is the
     * pre-answer candidate set; the caller resolves each call's own budget and
     * ignores the ones that are not due.
     */
    @Query(value = "SELECT s.* FROM call_sessions s "
            + "WHERE s.deleted_at IS NULL "
            + "AND s.deadline_at IS NULL "
            + "AND s.answered_at IS NULL "
            + "AND s.status IN :statuses "
            + "AND EXISTS (SELECT 1 FROM call_attempts a "
            + "            WHERE a.id = s.call_attempt_id "
            + "            AND a.deleted_at IS NULL "
            + "            AND a.status = 'IN_PROGRESS' "
            + "            AND a.started_at IS NOT NULL "
            + "            AND a.started_at <= :cutoff) "
            + "ORDER BY s.initiated_at",
            countQuery = "SELECT count(*) FROM call_sessions s "
                    + "WHERE s.deleted_at IS NULL AND s.deadline_at IS NULL "
                    + "AND s.answered_at IS NULL AND s.status IN :statuses",
            nativeQuery = true)
    List<CallSession> findLiveUnansweredSessionsDispatchedBefore(
            @Param("statuses") List<String> statuses,
            @Param("cutoff") Instant cutoff,
            org.springframework.data.domain.Pageable pageable);
}