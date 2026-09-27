package com.shivang.obd.voice.dtmf;

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
 * Repository for DTMF collection interactions.
 * <p>
 * All tenant-scoped lookups use the {@code tenant_id + deleted_at} pattern
 * the platform enforces elsewhere; cross-tenant references fail closed by
 * simply not resolving.
 */
@Repository
public interface DtmfInteractionRepository extends JpaRepository<DtmfInteraction, UUID> {

    /** The (single) interaction for a call session. */
    Optional<DtmfInteraction> findByCallSessionIdAndDeletedAtIsNull(UUID callSessionId);

    /** Tenant-scoped lookup — cross-tenant IDs never resolve (fail closed). */
    Optional<DtmfInteraction> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    /**
     * Timeout scan: non-terminal interactions past their collection deadline.
     * Native SQL on purpose: binding the NAMED_ENUM result as a derived-query
     * parameter makes Hibernate cast it to the Java-class-derived type name
     * ("dtmfresulttype"), which does not exist in PostgreSQL — the native
     * string literal resolves against the real {@code dtmf_result_type} enum.
     */
    @Query(value = "SELECT * FROM dtmf_interactions "
            + "WHERE result = 'COLLECTING' AND expires_at < :deadline AND deleted_at IS NULL",
            nativeQuery = true)
    List<DtmfInteraction> findExpired(@Param("deadline") Instant deadline);

    /**
     * Atomically claims a still-COLLECTING interaction into a terminal
     * result, recording the final collected digits. The conditional bulk
     * update makes terminalization idempotent even when the timeout poller
     * and the event thread race on the same interaction — exactly one claim
     * wins; the loser sees 0 rows. Native SQL on purpose (see
     * {@link #findExpired}); the result is bound as its enum name string and
     * explicitly cast, because Hibernate binds enum parameters in native
     * queries as ordinals.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE dtmf_interactions SET result = CAST(:result AS dtmf_result_type), "
            + "result_reason = :reason, result_at = :at, collected_digits = :collected "
            + "WHERE id = :id AND result = 'COLLECTING'", nativeQuery = true)
    int claimTerminal(@Param("id") UUID id,
                      @Param("result") String result,
                      @Param("reason") String reason,
                      @Param("at") Instant at,
                      @Param("collected") String collected);
}
