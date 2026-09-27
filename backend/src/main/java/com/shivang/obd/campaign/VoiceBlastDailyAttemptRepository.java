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
 * Persistence boundary of the Voice Blast daily campaign-attempt safety
 * ledger (VB-6D.3).
 *
 * <p>Two statements, both {@code @Modifying} native operations on the unique
 * bucket row, following the {@link VoiceBlastDailyUsageRepository} pattern
 * established by VB-6C.1:
 * <ul>
 *   <li>{@link #insertBucketRow} — idempotent creation, {@code ON CONFLICT
 *       DO NOTHING} so a concurrent creator never aborts the caller's
 *       transaction;</li>
 *   <li>{@link #reserve} — the admission itself: a single conditional
 *       {@code UPDATE} that increments the counter only while it is below
 *       the effective limit. The row lock is the concurrency authority, so
 *       {@code admitted <= limit} holds for any number of concurrent
 *       workers with no advisory lock and no read-then-write race.</li>
 * </ul>
 *
 * <p><b>Why there is no {@code release}.</b> VB-6C must release a hold
 * because it counts at provider acceptance, which happens after the
 * admitting transaction commits. This ledger counts at dial issuance, inside
 * that transaction, so the increment is already final. A decrement method
 * would be dead code that only invites a bug: a released "attempt" was still
 * a dial, and the contact may well have rung.
 */
@Repository
public interface VoiceBlastDailyAttemptRepository
        extends JpaRepository<VoiceBlastDailyAttempt, UUID> {

    Optional<VoiceBlastDailyAttempt> findByTenantIdAndContactIdAndUsageDate(
            UUID tenantId, UUID contactId, LocalDate usageDate);

    /**
     * Idempotent bucket-row creation. Never raises, never aborts the
     * caller's transaction.
     */
    @Modifying
    @Query(value = "INSERT INTO voice_blast_daily_attempts "
            + "(id, tenant_id, contact_id, usage_date, attempt_count) "
            + "VALUES (gen_random_uuid(), :tenantId, :contactId, :usageDate, 0) "
            + "ON CONFLICT (tenant_id, contact_id, usage_date) DO NOTHING",
            nativeQuery = true)
    void insertBucketRow(@Param("tenantId") UUID tenantId,
                         @Param("contactId") UUID contactId,
                         @Param("usageDate") LocalDate usageDate);

    /**
     * Atomic admission: consumes one attempt and grants the dispatch only
     * while the bucket is below the effective limit. Row-locks the bucket,
     * serializing concurrent workers on the same contact-day.
     *
     * @return 1 iff the caller won the slot, 0 when the ceiling is reached
     */
    @Modifying
    @Query(value = "UPDATE voice_blast_daily_attempts "
            + "SET attempt_count = attempt_count + 1, updated_at = now() "
            + "WHERE tenant_id = :tenantId AND contact_id = :contactId "
            + "AND usage_date = :usageDate "
            + "AND attempt_count < :effectiveLimit",
            nativeQuery = true)
    int reserve(@Param("tenantId") UUID tenantId,
                @Param("contactId") UUID contactId,
                @Param("usageDate") LocalDate usageDate,
                @Param("effectiveLimit") int effectiveLimit);

    /**
     * Attempts consumed so far for a bucket. Verification and reporting seam,
     * used by the concurrency suite to assert the invariant from committed
     * database state rather than from in-memory counters.
     */
    @Query(value = "SELECT COALESCE(attempt_count, 0) FROM voice_blast_daily_attempts "
            + "WHERE tenant_id = :tenantId AND contact_id = :contactId "
            + "AND usage_date = :usageDate",
            nativeQuery = true)
    Integer currentCount(@Param("tenantId") UUID tenantId,
                         @Param("contactId") UUID contactId,
                         @Param("usageDate") LocalDate usageDate);
}
