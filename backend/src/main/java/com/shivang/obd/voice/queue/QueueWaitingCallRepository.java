package com.shivang.obd.voice.queue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link QueueWaitingCall}. The partial unique index
 * {@code uq_queue_waiting_calls_active_session} guarantees one live
 * WAITING row per call session at the database level.
 */
public interface QueueWaitingCallRepository
        extends JpaRepository<QueueWaitingCall, UUID>,
                JpaSpecificationExecutor<QueueWaitingCall> {

    List<QueueWaitingCall> findByQueueIdAndTenantIdAndStatusAndDeletedAtIsNullOrderByEnteredAtAscIdAsc(
            UUID queueId, UUID tenantId, QueueWaitingCallStatus status);

    Optional<QueueWaitingCall> findByCallSessionIdAndStatusAndDeletedAtIsNull(
            UUID callSessionId, QueueWaitingCallStatus status);

    /** Live waiting/assigned row for a session regardless of status (VB-4D lookup). */
    Optional<QueueWaitingCall> findByCallSessionIdAndDeletedAtIsNull(UUID callSessionId);

    long countByQueueIdAndTenantIdAndStatusAndDeletedAtIsNull(
            UUID queueId, UUID tenantId, QueueWaitingCallStatus status);

    Page<QueueWaitingCall> findAll(Specification<QueueWaitingCall> spec, Pageable pageable);

    // === VB-4C ACD extensions ==================================================

    /** Live (non-deleted) waiting call lookup — ACD's eligibility gate. */
    Optional<QueueWaitingCall> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * All live WAITING rows in FIFO dispatch order (enteredAt, id — the
     * VB-4B ordering policy). Consumed by the VB-4D ACD retry sweep.
     */
    List<QueueWaitingCall> findByStatusAndDeletedAtIsNullOrderByEnteredAtAscIdAsc(
            QueueWaitingCallStatus status);

    /**
     * Atomic assignment claim: WAITING → ASSIGNED with the agent/
     * reservation references. Exactly one racing ACD attempt wins;
     * losers update 0 rows and must treat the call as already assigned
     * (idempotent path). Native SQL: JPQL enum literals are unreliable
     * with Hibernate 7 native-enum columns.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "update queue_waiting_calls "
            + "set status = 'ASSIGNED', assigned_agent_id = :agentId, "
            + "assigned_reservation_id = :reservationId, assigned_at = now(), updated_at = now() "
            + "where id = :id and status = 'WAITING' and deleted_at is null",
            nativeQuery = true)
    int claimAssignment(@org.springframework.lang.NonNull @Param("id") UUID id,
                        @Param("agentId") UUID agentId,
                        @Param("reservationId") UUID reservationId);

    /**
     * Queue-timeout sweep: WAITING rows past their persisted expiry become
     * ABANDONED. ASSIGNED rows are never abandoned by the timeout worker
     * (an assigned call is being connected). Idempotent.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "update queue_waiting_calls "
            + "set status = 'ABANDONED', updated_at = now() "
            + "where status = 'WAITING' and expires_at is not null and expires_at < :now "
            + "and deleted_at is null",
            nativeQuery = true)
    int abandonExpiredWaitingCalls(@Param("now") java.time.Instant now);

    /** Waiting rows past expiry (pre-read for the reconciler's unwinding). */
    @org.springframework.data.jpa.repository.Query(value = "select id from queue_waiting_calls "
            + "where status = 'WAITING' and expires_at is not null and expires_at < :now "
            + "and deleted_at is null", nativeQuery = true)
    List<UUID> findExpiredWaitingCallIds(@Param("now") java.time.Instant now);

    /** Waiting rows for a set of queues in deterministic dispatch order. */
    List<QueueWaitingCall> findByQueueIdInAndStatusAndDeletedAtIsNullOrderByEnteredAtAscIdAsc(
            List<UUID> queueIds, QueueWaitingCallStatus status);

    /**
     * Returns an ASSIGNED call to WAITING and clears the assignment
     * references (used when the backing reservation was released or
     * expired). Conditional on the ASSIGNED marker — idempotent.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "update queue_waiting_calls "
            + "set status = 'WAITING', assigned_agent_id = null, "
            + "assigned_reservation_id = null, assigned_at = null, updated_at = now() "
            + "where id = :id and status = 'ASSIGNED' and deleted_at is null",
            nativeQuery = true)
    int returnToWaiting(@Param("id") UUID id);

    /**
     * Returns the ASSIGNED call backed by {@code reservationId} to
     * WAITING (reservation-expiry unwind). Conditional — idempotent.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "update queue_waiting_calls "
            + "set status = 'WAITING', assigned_agent_id = null, "
            + "assigned_reservation_id = null, assigned_at = null, updated_at = now() "
            + "where assigned_reservation_id = :reservationId and status = 'ASSIGNED' "
            + "and deleted_at is null",
            nativeQuery = true)
    int returnToWaitingByReservation(@Param("reservationId") UUID reservationId);

    /**
     * Overflow move: WAITING rows of the source queue become rows of the
     * target queue (same tenant enforced by the caller). Conditional on
     * WAITING — idempotent, bounded to one explicit hop; loop prevention
     * is structural (see AcdOverflowService).
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "update queue_waiting_calls "
            + "set queue_id = :targetId, expires_at = null, updated_at = now() "
            + "where queue_id = :sourceId and status = 'WAITING' and tenant_id = :tenantId "
            + "and deleted_at is null",
            nativeQuery = true)
    int moveWaitingCallsToQueue(@Param("sourceId") UUID sourceId,
                                @Param("targetId") UUID targetId,
                                @Param("tenantId") UUID tenantId);

    /**
     * Caller gave up while queued (VB-4D): conditional WAITING→REMOVED,
     * idempotent. ASSIGNED rows are never removed here — their cleanup
     * is owned by the reservation release path, not the caller's queue
     * membership.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "update queue_waiting_calls "
            + "set status = 'REMOVED', updated_at = now() "
            + "where call_session_id = :sessionId and status = 'WAITING' and deleted_at is null",
            nativeQuery = true)
    int markWaitingRemovedForSession(@Param("sessionId") UUID sessionId);
}
