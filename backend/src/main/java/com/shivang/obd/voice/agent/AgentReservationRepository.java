package com.shivang.obd.voice.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link AgentReservation}. All state transitions are
 * conditional UPDATEs (atomic claims) — see
 * {@link AgentReservationService} (VB-3).
 * <p>
 * Queries involving the native {@code agent_reservation_status} enum use
 * NATIVE SQL with string literals: PostgreSQL infers the enum cast from
 * column context. JPQL enum literals are unreliable here (Hibernate 7 binds
 * them as {@code 'X'::JavaClassName}, which is not a PG type) — the same
 * VB-2 {@code claimTerminal} lesson.
 */
public interface AgentReservationRepository
        extends JpaRepository<AgentReservation, UUID>,
                JpaSpecificationExecutor<AgentReservation> {

    Optional<AgentReservation> findByCallSessionIdAndDeletedAtIsNull(UUID callSessionId);

    Optional<AgentReservation> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    List<AgentReservation> findByAgentIdAndTenantIdAndDeletedAtIsNull(UUID agentId, UUID tenantId);

    /** Count of active holds for an agent — the atomic concurrency-check basis. */
    @Query(value = "select count(*) from agent_reservations r "
            + "where r.agent_id = :agentId and r.status <> 'RELEASED' and r.deleted_at is null",
            nativeQuery = true)
    int countActiveByAgentId(@Param("agentId") UUID agentId);

    /**
     * Promotes a hold from RESERVED to ACTIVE — the unique atomic transition
     * into ACTIVE. Exactly one racing caller wins; losers update 0 rows and
     * must treat the reservation as already-active (idempotent path).
     */
    @Modifying
    @Query(value = "update agent_reservations set status = 'ACTIVE', updated_at = now() "
            + "where id = :id and status = 'RESERVED' and deleted_at is null",
            nativeQuery = true)
    int promoteToActive(@Param("id") UUID id);

    /**
     * Releases any active reservation (RESERVED/ACTIVE) held for a call
     * session — the caller/agent hangup and failure paths. Idempotent:
     * already-RELEASED rows update 0 rows.
     */
    @Modifying
    @Query(value = "update agent_reservations set status = 'RELEASED', released_at = now(), "
            + "release_reason = :reason, updated_at = now() "
            + "where call_session_id = :callSessionId and status <> 'RELEASED' and deleted_at is null",
            nativeQuery = true)
    int releaseByCallSession(@Param("callSessionId") UUID callSessionId,
                             @Param("reason") String reason);

    /**
     * Releases the specific reservation identified by {@code id} from any
     * active state — idempotent: already-RELEASED rows update 0 rows.
     */
    @Modifying
    @Query(value = "update agent_reservations set status = 'RELEASED', released_at = now(), "
            + "release_reason = :reason, updated_at = now() "
            + "where id = :id and status <> 'RELEASED' and deleted_at is null",
            nativeQuery = true)
    int releaseReservation(@Param("id") UUID id, @Param("reason") String reason);

    /**
     * ACD release by waiting call: releases the reservation tied to a
     * waiting call's assignment (joins on waiting_call_id). Idempotent.
     */
    @Modifying
    @Query(value = "update agent_reservations set status = 'RELEASED', released_at = now(), "
            + "release_reason = :reason, updated_at = now() "
            + "where waiting_call_id = :waitingCallId and status <> 'RELEASED' and deleted_at is null",
            nativeQuery = true)
    int releaseByWaitingCall(@Param("waitingCallId") UUID waitingCallId,
                             @Param("reason") String reason);

    /**
     * Stale-reservation reclamation: RESERVED holds older than the cutoff
     * (originating crashed mid-flight). ACTIVE holds are never reclaimed
     * here — the bridge/hangup paths own those. Idempotent.
     */
    @Modifying
    @Query(value = "update agent_reservations set status = 'RELEASED', released_at = now(), "
            + "release_reason = 'STALE_RECLAIM', updated_at = now() "
            + "where status = 'RESERVED' and reserved_at < :cutoff and deleted_at is null",
            nativeQuery = true)
    int releaseStaleReservations(@Param("cutoff") Instant cutoff);

    /**
     * Agents whose RESERVED holds predate the cutoff — evaluated before the
     * stale sweep so availability can be restored for agents whose last
     * active hold was reclaimed (BUSY is owned by the reservation lifecycle).
     */
    @org.springframework.data.jpa.repository.Query(value = "select distinct agent_id from agent_reservations "
            + "where status = 'RESERVED' and reserved_at < :cutoff and deleted_at is null",
            nativeQuery = true)
    java.util.List<java.util.UUID> findAgentIdsWithStaleReservations(@Param("cutoff") Instant cutoff);

    // === VB-4C ACD extensions ==================================================

    /**
     * ACD expiry sweep: unclaimed ACD reservations (RESERVED, has an
     * expiry) past their window are released. VB-3 holds (no expiry) and
     * ACTIVE holds are untouched. Idempotent.
     */
    @Modifying
    @Query(value = "update agent_reservations set status = 'RELEASED', released_at = now(), "
            + "release_reason = :reason, updated_at = now() "
            + "where status = 'RESERVED' and expires_at is not null and expires_at < :now "
            + "and deleted_at is null",
            nativeQuery = true)
    int releaseExpiredReservations(@Param("now") Instant now, @Param("reason") String reason);

    /**
     * Releases expired reservations and returns the released rows' ids
     * (for the ACD reconciler to unwind the waiting-call assignments).
     */
    @org.springframework.data.jpa.repository.Query(value = "select id from agent_reservations "
            + "where status = 'RELEASED' and release_reason = :reason and released_at >= :since",
            nativeQuery = true)
    List<UUID> findIdsReleasedWithReasonSince(@Param("reason") String reason,
                                              @Param("since") Instant since);

    /**
     * Distinct agents whose holds were released with {@code reason} since
     * the instant (for availability restoration after ACD expiry).
     */
    @org.springframework.data.jpa.repository.Query(value = "select distinct agent_id from agent_reservations "
            + "where status = 'RELEASED' and release_reason = :reason and released_at >= :since",
            nativeQuery = true)
    List<UUID> findAgentIdsReleasedWithReasonSince(@Param("reason") String reason,
                                                   @Param("since") Instant since);
}
