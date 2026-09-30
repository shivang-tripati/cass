package com.shivang.obd.campaign;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence boundary of the Voice Blast daily dial-limit ledger
 * (VB-6C.1).
 * <p>
 * The three lifecycle operations are single-statement conditional
 * UPDATEs on the unique bucket row — the conditional WHERE clause makes
 * PostgreSQL's row lock the concurrency authority (no read-then-write
 * race, no advisory locks, no new infrastructure). All three are
 * {@code @Modifying} native operations following the established
 * {@code DtmfInteractionRepository.claimTerminal} pattern:
 * <ul>
 *   <li>{@link #reserve} — atomic admission: grants a hold only while
 *       {@code reserved_count + used_count < :effectiveLimit}; returns 1
 *       iff this caller won the slot.</li>
 *   <li>{@link #confirmUsed} — atomic consumption at provider acceptance
 *       (+OK): converts the hold into a permanent count and is a no-op
 *       (0 rows) if no hold exists.</li>
 *   <li>{@link #releaseReservation} — returns a hold when the dial never
 *       reached the provider; a no-op (0 rows) when there is nothing to
 *       release.</li>
 * </ul>
 * The native-SQL-on-purpose rationale matches the DTMF repository: the
 * arithmetic guard ({@code < :effectiveLimit}) cannot be expressed as a
 * derived query, and the conditional UPDATE is the whole point of the
 * design.
 */
@Repository
public interface VoiceBlastDailyUsageRepository
        extends JpaRepository<VoiceBlastDailyUsage, UUID> {

    Optional<VoiceBlastDailyUsage> findByTenantIdAndContactIdAndDidIdAndUsageDate(
            UUID tenantId, UUID contactId, UUID didId, LocalDate usageDate);

    /**
     * Observability only (VB-6C.3): how many buckets currently hold an
     * un-released reservation. Normally zero — every hold is released on a
     * pre-acceptance failure and converted on provider acceptance. A
     * non-zero value therefore means a process died between reservation and
     * resolution and left capacity stranded until the bucket ages out.
     * <p>
     * Deliberately a plain diagnostic read, not a reconciliation mechanism:
     * nothing sweeps these rows in this phase (§7/§8 — observe first,
     * reconcile only on evidence).
     */
    @Query(value = "SELECT COUNT(*) FROM voice_blast_daily_usage "
            + "WHERE reserved_count > 0", nativeQuery = true)
    long countBucketsWithReservations();

    /**
     * Idempotent bucket-row creation: atomic INSERT that is a no-op when a
     * concurrent creator (or an earlier call) already made the row.
     * Deliberately NOT a saveAndFlush-then-catch: a unique-key violation
     * would mark the caller's transaction rollback-only and poison the
     * subsequent conditional UPDATE, while ON CONFLICT DO NOTHING never
     * raises and never aborts.
     */
    @Modifying
    @Query(value = "INSERT INTO voice_blast_daily_usage "
            + "(id, tenant_id, contact_id, did_id, usage_date, reserved_count, used_count) "
            + "VALUES (gen_random_uuid(), :tenantId, :contactId, :didId, :usageDate, 0, 0) "
            + "ON CONFLICT (tenant_id, contact_id, did_id, usage_date) DO NOTHING",
            nativeQuery = true)
    void insertBucketRow(@Param("tenantId") UUID tenantId,
                         @Param("contactId") UUID contactId,
                         @Param("didId") UUID didId,
                         @Param("usageDate") LocalDate usageDate);

    /**
     * Atomic admission. Creates no rows and grants no slot when the
     * bucket is at/over the effective limit. Row-locks the bucket for
     * the caller's transaction, serializing same-bucket workers.
     *
     * @return 1 iff a slot was granted, 0 when the bucket is exhausted
     */
    @Modifying
    @Query(value = "UPDATE voice_blast_daily_usage "
            + "SET reserved_count = reserved_count + 1, updated_at = now() "
            + "WHERE tenant_id = :tenantId AND contact_id = :contactId "
            + "AND did_id = :didId AND usage_date = :usageDate "
            + "AND reserved_count + used_count < :effectiveLimit",
            nativeQuery = true)
    int reserve(@Param("tenantId") UUID tenantId,
                @Param("contactId") UUID contactId,
                @Param("didId") UUID didId,
                @Param("usageDate") LocalDate usageDate,
                @Param("effectiveLimit") int effectiveLimit);

    /**
     * Atomic consumption at provider acceptance (+OK). No-op unless the
     * caller currently holds a reservation (the dial path always does),
     * so a spurious confirm can never inflate usage.
     *
     * @return 1 iff the hold was converted into a permanent count
     */
    @Modifying
    @Query(value = "UPDATE voice_blast_daily_usage "
            + "SET used_count = used_count + 1, reserved_count = reserved_count - 1, "
            + "updated_at = now() "
            + "WHERE tenant_id = :tenantId AND contact_id = :contactId "
            + "AND did_id = :didId AND usage_date = :usageDate "
            + "AND reserved_count > 0",
            nativeQuery = true)
    int confirmUsed(@Param("tenantId") UUID tenantId,
                    @Param("contactId") UUID contactId,
                    @Param("didId") UUID didId,
                    @Param("usageDate") LocalDate usageDate);

    /**
     * Returns a hold after a pre-acceptance failure (the dial never
     * reached the provider). No-op when nothing is held.
     *
     * @return 1 iff a hold was released
     */
    @Modifying
    @Query(value = "UPDATE voice_blast_daily_usage "
            + "SET reserved_count = reserved_count - 1, updated_at = now() "
            + "WHERE tenant_id = :tenantId AND contact_id = :contactId "
            + "AND did_id = :didId AND usage_date = :usageDate "
            + "AND reserved_count > 0",
            nativeQuery = true)
    int releaseReservation(@Param("tenantId") UUID tenantId,
                           @Param("contactId") UUID contactId,
                           @Param("didId") UUID didId,
                           @Param("usageDate") LocalDate usageDate);

    /**
     * VB-8G: records a provider-accepted dial whose pre-dial hold no longer
     * exists, because the transaction that granted it rolled back after
     * FreeSWITCH had already accepted the call.
     *
     * <p>Deliberately the one operation in this repository with <b>no</b> guard,
     * and both absences are load-bearing:
     *
     * <ul>
     *   <li><b>No {@code reserved_count > 0} guard</b> (unlike
     *       {@link #confirmUsed}). There is no hold to convert, so that guard
     *       would silently drop the usage and leave the bucket permanently one
     *       behind reality - the exact defect this operation exists to close.
     *       It also cannot corrupt the hold count, because {@code
     *       reserved_count} is never touched here.</li>
     *   <li><b>No {@code < :effectiveLimit} guard</b> (unlike
     *       {@link #reserve}). Admission is the only operation allowed to
     *       refuse a slot, and this is not an admission: the provider already
     *       accepted the call, so the quota cannot retroactively un-place it.
     *       Refusing here would erase a factual acceptance and permit further
     *       dials. The bucket is therefore allowed to exceed the limit, and
     *       subsequent admissions observe that and correctly stop.</li>
     * </ul>
     *
     * <p>Idempotency is <b>not</b> this statement's job. It is deliberately a
     * plain unconditional increment, because a guarded increment cannot
     * distinguish "first reconciliation" from "duplicate event". The
     * exactly-once guarantee comes from the caller inserting the
     * {@code UNIQUE (call_attempt_id)} ledger row first
     * ({@code DailyDialLimitService.reconcileRecoveredAcceptance}); only the
     * worker that wins that insert ever reaches this method.
     *
     * <p>Row-locked like its siblings, so concurrent recoveries for the same
     * bucket serialise, and a concurrent admission's
     * {@code reserved_count + used_count} check observes the reconciled usage.
     *
     * @return 1 iff the bucket row existed and was counted
     */
    @Modifying
    @Query(value = "UPDATE voice_blast_daily_usage "
            + "SET used_count = used_count + 1, updated_at = now() "
            + "WHERE tenant_id = :tenantId AND contact_id = :contactId "
            + "AND did_id = :didId AND usage_date = :usageDate",
            nativeQuery = true)
    int countRecoveredUsed(@Param("tenantId") UUID tenantId,
                           @Param("contactId") UUID contactId,
                           @Param("didId") UUID didId,
                           @Param("usageDate") LocalDate usageDate);
}
