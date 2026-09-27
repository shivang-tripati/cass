package com.shivang.obd.voice.ivr;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Access to per-node IVR call state.
 * <p>
 * The three methods that matter for correctness mirror the proven DTMF runtime
 * one-for-one: {@link #findCurrent} is {@code findByCallSessionId} generalised
 * from one row to the newest of many, {@link #findExpired} is the same timeout
 * scan, and {@link #claimTerminal} is the same atomic claim. Reusing that
 * idiom is why duplicate and late DTMF events need no special handling here.
 */
@Repository
public interface IvrStepRepository extends JpaRepository<IvrStep, UUID> {

    Optional<IvrStep> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    /** Every visit for a call, oldest first — the auditable path the caller took. */
    List<IvrStep> findByCallSessionIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID callSessionId);

    /**
     * The call's current node visit: the newest one that has not finished.
     * <p>
     * This single query is the whole of "where is the caller in the tree", which
     * is the same shape as the single-level runtime's one-lookup interaction
     * lookup. Returns empty once the IVR has finished, so a late digit after
     * completion is a no-op rather than a re-entry.
     */
    Optional<IvrStep> findFirstByCallSessionIdAndResultAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID callSessionId, IvrStepResultType result);

    /**
     * Timeout scan: non-terminal steps past their per-node deadline, consumed by
     * the existing 1-second DTMF poller.
     * <p>
     * Native SQL for the same reason as the DTMF equivalent: binding a
     * {@code NAMED_ENUM} result as a derived-query parameter makes Hibernate cast
     * it to the Java-class-derived type name, which does not exist in
     * PostgreSQL. A native string literal resolves against the real enum type.
     */
    @Query(value = "SELECT * FROM ivr_steps "
            + "WHERE result = 'WAITING_INPUT' AND expires_at < :deadline AND deleted_at IS NULL",
            nativeQuery = true)
    List<IvrStep> findExpired(@Param("deadline") Instant deadline);

    /**
     * Atomically claims a still-waiting step into a terminal result.
     * <p>
     * The conditional bulk update is what makes terminalization idempotent when
     * the timeout poller, the DTMF event thread and a duplicate digit event all
     * race on the same visit: exactly one claim wins, the losers see zero rows
     * and do nothing. Same idiom, and the same native-SQL reason, as
     * {@code DtmfInteractionRepository.claimTerminal}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE ivr_steps SET result = CAST(:result AS ivr_step_result_type), "
            + "result_reason = :reason, result_at = :at "
            + "WHERE id = :id AND result = 'WAITING_INPUT'", nativeQuery = true)
    int claimTerminal(@Param("id") UUID id,
                      @Param("result") String result,
                      @Param("reason") String reason,
                      @Param("at") Instant at);
}
