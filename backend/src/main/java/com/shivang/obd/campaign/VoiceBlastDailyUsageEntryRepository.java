package com.shivang.obd.campaign;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Per-attempt usage ledger access (VB-6C.1). Reads are for verification
 * and future reporting; the {@code UNIQUE (call_attempt_id)} constraint
 * (V47) is the duplicate-protection authority — the insert path simply
 * relies on it via the repository save.
 */
@Repository
public interface VoiceBlastDailyUsageEntryRepository
        extends JpaRepository<VoiceBlastDailyUsageEntry, UUID> {

    Optional<VoiceBlastDailyUsageEntry> findByCallAttemptId(UUID callAttemptId);

    boolean existsByCallAttemptId(UUID callAttemptId);

    /**
     * VB-8G: inserts the per-attempt usage row as the <b>idempotency gate</b>
     * for a recovered acceptance, and reports whether this caller was the one
     * that created it.
     *
     * <p>{@code ON CONFLICT (call_attempt_id) DO NOTHING} rather than a
     * save-then-catch, for exactly the reason
     * {@link VoiceBlastDailyUsageRepository#insertBucketRow} gives: a unique
     * violation would mark the caller's transaction rollback-only and poison
     * the follow-up bucket increment, whereas this form never raises and never
     * aborts. A duplicate event simply returns 0 and the caller counts nothing.
     *
     * <p>It also orders the two writes correctly. The entry is inserted
     * <em>before</em> the bucket increment, so the increment is gated by a
     * physical uniqueness check rather than by a read that could race. The
     * normal {@link DailyDialLimitService#confirmAccepted} path takes the
     * opposite order and does not need this: there a hold is the scarce
     * resource, so the losing racer's guarded {@code confirmUsed} matches no
     * row and it has counted nothing to roll back.
     *
     * @return 1 iff this caller created the entry, 0 if it already existed
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value =
            "INSERT INTO voice_blast_daily_usage_entries "
                    + "(id, tenant_id, call_attempt_id, contact_id, did_id, usage_date, "
                    + " provider_call_id, created_at) "
                    + "VALUES (gen_random_uuid(), :tenantId, :callAttemptId, :contactId, "
                    + ":didId, :usageDate, :providerCallId, now()) "
                    + "ON CONFLICT (call_attempt_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                       @org.springframework.data.repository.query.Param("callAttemptId")
                                       UUID callAttemptId,
                       @org.springframework.data.repository.query.Param("contactId") UUID contactId,
                       @org.springframework.data.repository.query.Param("didId") UUID didId,
                       @org.springframework.data.repository.query.Param("usageDate")
                                       java.time.LocalDate usageDate,
                       @org.springframework.data.repository.query.Param("providerCallId")
                                       String providerCallId);
}
