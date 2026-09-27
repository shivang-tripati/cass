package com.shivang.obd.campaign;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Campaign-owned runtime foundation of the Voice Blast daily dial-limit
 * policy (VB-6C.1). Owns exactly one concern — the daily-limit ledger
 * lifecycle around one provider dial:
 *
 * <pre>
 * admission (reserve)  →  provider acceptance (+OK)  →  usage (confirm)
 *          \→ any pre-acceptance rejection → return the hold (release)
 * </pre>
 *
 * <p>Deliberate non-ownership (audit §14): contact management, DID
 * routing, capacity, campaign scheduling, FreeSWITCH communication. The
 * dial pipeline ({@link OutboundDialService}) calls this service at two
 * boundaries — after route selection (admission) and in the
 * {@code DIAL_REQUEST_ACCEPTED} branch (usage) — and never touches the
 * ledger tables itself.</p>
 *
 * <p><b>Semantics.</b> A slot is <em>consumed</em> only when the provider
 * accepted the originate (ESL {@code +OK <uuid>}, the
 * {@code DIAL_REQUEST_ACCEPTED} boundary). Everything before acceptance
 * — routing/eligibility/capacity rejections, provider unavailable,
 * originate failures — returns its hold and consumes nothing. Everything
 * after acceptance (ring, no-answer, busy, hangup, post-acceptance
 * failure) counts.</p>
 *
 * <p><b>Concurrency.</b> All counter transitions are single-statement
 * conditional UPDATEs on the unique bucket row
 * ({@code (tenant_id, contact_id, did_id, usage_date)}); PostgreSQL row
 * locking makes over-admission impossible without any distributed
 * coordination. The effective limit is {@code min(campaignDailyLimit, 3)}
 * where 3 is the platform maximum; no campaign limit is configurable in
 * this phase (VB-6C.2), so the platform max always applies.</p>
 *
 * <p><b>Timezone.</b> The usage day is computed in the execution
 * snapshot's IANA timezone — the authoritative zone of the Voice Blast
 * schedule. A null/blank/invalid zone fails the dial deterministically
 * (see {@link ExecutionTimezoneInvalidException}); there is deliberately
 * no JVM/UTC fallback (audit §2).</p>
 */
@Service
@Slf4j
public class DailyDialLimitService {

    /** Platform-wide maximum accepted dials per bucket per day. */
    public static final int PLATFORM_DAILY_DIAL_LIMIT = 3;

    /**
     * The canonical upper bound of the campaign-configurable daily dial
     * limit (VB-6C.2) — the same platform maximum under its API-facing
     * name. DTO annotation, service validation, and the V48 CHECK
     * constraint all derive from this single constant; no magic numbers.
     */
    public static final int MAX_VOICE_BLAST_DAILY_DIAL_LIMIT = PLATFORM_DAILY_DIAL_LIMIT;

    private final VoiceBlastDailyUsageRepository usageRepository;
    private final VoiceBlastDailyUsageEntryRepository entryRepository;
    private final Clock clock;

    /** Production constructor: UTC-pinned clock — the snapshot zone supplies the day. */
    @org.springframework.beans.factory.annotation.Autowired
    public DailyDialLimitService(VoiceBlastDailyUsageRepository usageRepository,
                                 VoiceBlastDailyUsageEntryRepository entryRepository) {
        this(usageRepository, entryRepository, Clock.systemUTC());
    }

    /**
     * Test-visible constructor; {@code clock} fixes "today"
     * deterministically (also used by the PostgreSQL integration suite to
     * pin the calendar-day boundary assertions).
     */
    public DailyDialLimitService(VoiceBlastDailyUsageRepository usageRepository,
                                 VoiceBlastDailyUsageEntryRepository entryRepository,
                                 Clock clock) {
        this.usageRepository = usageRepository;
        this.entryRepository = entryRepository;
        this.clock = clock;
    }

    // === policy helpers ===

    /**
     * The effective limit for a bucket: never above the platform maximum
     * of {@value #PLATFORM_DAILY_DIAL_LIMIT}. In this phase no campaign
     * limit is configurable, so the platform max always applies
     * (VB-6C.2 will thread the snapshot value through
     * {@code CampaignRuntimeConfig}).
     */
    public int effectiveLimit(Integer campaignDailyLimit) {
        if (campaignDailyLimit == null) {
            return PLATFORM_DAILY_DIAL_LIMIT;
        }
        return Math.min(campaignDailyLimit, PLATFORM_DAILY_DIAL_LIMIT);
    }

    /**
     * Domain-level guard for the configured value (VB-6C.2): null is the
     * platform default; 1..{@value #MAX_VOICE_BLAST_DAILY_DIAL_LIMIT} is a
     * valid stricter limit; anything else is rejected. Protects entities
     * constructed outside the REST boundary (DTO validation covers that
     * path; the DB CHECK is the last line of defense) — one canonical rule,
     * three boundaries, no duplicated logic.
     */
    public static void assertConfigurable(Integer campaignDailyLimit) {
        if (campaignDailyLimit == null) {
            return;
        }
        if (campaignDailyLimit < 1
                || campaignDailyLimit > MAX_VOICE_BLAST_DAILY_DIAL_LIMIT) {
            // BusinessException (VALIDATION_ERROR) maps onto the platform's
            // existing 400 ProblemDetail contract at every boundary.
            throw new com.shivang.obd.common.exception.BusinessException(
                    com.shivang.obd.common.api.error.CommonErrorCode.VALIDATION_ERROR,
                    "dailyDialLimit must be between 1 and "
                            + MAX_VOICE_BLAST_DAILY_DIAL_LIMIT
                            + ", or null for the platform maximum");
        }
    }

    /**
     * The usage day for a dial: today's date in the execution snapshot's
     * IANA timezone. Fails deterministically when the snapshot has no
     * valid zone — no silent JVM/UTC fallback.
     *
     * @throws ExecutionTimezoneInvalidException when the snapshot timezone
     *         is null, blank, or not a valid IANA identifier
     */
    public LocalDate resolveUsageDate(String snapshotTimezone) {
        if (snapshotTimezone == null || snapshotTimezone.isBlank()) {
            throw new ExecutionTimezoneInvalidException(
                    "Voice Blast execution snapshot has no timezone; daily dial limit "
                            + "cannot determine the usage day. Configure the campaign "
                            + "schedule timezone.");
        }
        try {
            ZoneId zone = ZoneId.of(snapshotTimezone.trim());
            return LocalDate.now(clock.withZone(zone));
        } catch (Exception invalidZone) {
            throw new ExecutionTimezoneInvalidException(
                    "Voice Blast execution snapshot timezone '" + snapshotTimezone
                            + "' is not a valid IANA identifier; daily dial limit "
                            + "cannot determine the usage day.");
        }
    }

    // === ledger lifecycle (all operations transactional by the caller) ===

    /**
     * Result of an admission attempt.
     */
    public enum AdmissionResult { ADMITTED, DAILY_LIMIT_REACHED }

    /**
     * Atomically admits one dial into the bucket
     * {@code (tenantId, contactId, actualOutboundDidId, usageDate)}.
     * <p>
     * Admission is a HOLD, not a count: it must be resolved by
     * {@link #confirmAccepted} (provider accepted) or
     * {@link #releaseReservation} (dial never reached the provider).
     *
     * @return {@code ADMITTED} iff a slot was granted;
     *         {@code DAILY_LIMIT_REACHED} when the bucket is exhausted
     */
    public AdmissionResult admit(UUID tenantId, UUID contactId, UUID actualOutboundDidId,
                                 LocalDate usageDate, int effectiveLimit) {
        ensureBucketRow(tenantId, contactId, actualOutboundDidId, usageDate);
        int reserved = usageRepository.reserve(
                tenantId, contactId, actualOutboundDidId, usageDate, effectiveLimit);
        if (reserved == 1) {
            return AdmissionResult.ADMITTED;
        }
        log.info("Daily dial limit reached for tenant={} contact={} did={} date={} "
                        + "(effectiveLimit={})",
                tenantId, contactId, actualOutboundDidId, usageDate, effectiveLimit);
        return AdmissionResult.DAILY_LIMIT_REACHED;
    }

    /**
     * Records exactly one usage for a provider-accepted dial (+OK). The
     * per-attempt entry insert plus the bucket increment happen here; the
     * caller keeps both in the same transaction as the attempt update
     * that stamps {@code providerCallId}, so usage and attempt state are
     * consistent by construction.
     * <p>
     * Duplicate safety is layered: the ledger entry is unique on
     * {@code call_attempt_id} (physical guarantee — a replayed acceptance
     * cannot create a second row), and the bucket increment is guarded by
     * the caller's hold (a confirm without a hold does nothing). With a
     * normal single acceptance both effects happen exactly once.
     */
    public void confirmAccepted(UUID tenantId, UUID callAttemptId, UUID contactId,
                                UUID actualOutboundDidId, LocalDate usageDate,
                                String providerCallId) {
        // Fast-path idempotency: a replayed acceptance for an attempt whose
        // entry already exists is a pure no-op (no second insert, no second
        // increment, no transaction poisoning).
        if (entryRepository.existsByCallAttemptId(callAttemptId)) {
            log.info("Duplicate provider-acceptance usage suppressed for attempt {} "
                    + "(entry already recorded)", callAttemptId);
            return;
        }
        // Guarded by the caller's hold: a confirm without a reservation
        // cannot inflate usage (0-row update).
        usageRepository.confirmUsed(tenantId, contactId, actualOutboundDidId, usageDate);
        VoiceBlastDailyUsageEntry entry = new VoiceBlastDailyUsageEntry();
        entry.setTenantId(tenantId);
        entry.setCallAttemptId(callAttemptId);
        entry.setContactId(contactId);
        entry.setDidId(actualOutboundDidId);
        entry.setUsageDate(usageDate);
        entry.setProviderCallId(providerCallId);
        entry.setCreatedAt(java.time.Instant.now(clock));
        try {
            entryRepository.saveAndFlush(entry);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Raced duplicate of the same attempt's acceptance: the unique
            // call_attempt_id key wins. The losing transaction rolls back
            // WITHOUT having counted (its guarded confirmUpdated 0 rows —
            // the winner holds the only reservation), so the committed DB
            // state keeps exactly one entry and one usage for this attempt.
            log.info("Concurrent duplicate provider-acceptance suppressed for attempt {} "
                    + "(unique call_attempt_id key)", callAttemptId);
        }
    }

    /**
     * Returns a hold after a pre-acceptance failure (the dial never
     * reached the provider). Idempotent — releasing with nothing held is
     * a no-op. Never called after {@link #confirmAccepted}: a consumed
     * slot is permanent by policy.
     */
    public void releaseReservation(UUID tenantId, UUID contactId,
                                   UUID actualOutboundDidId, LocalDate usageDate) {
        usageRepository.releaseReservation(tenantId, contactId, actualOutboundDidId, usageDate);
    }

    /**
     * Current provider-accepted usage of a bucket (verification/reporting
     * seam; also used by tests to prove the invariant from PostgreSQL
     * state).
     */
    public int usedCount(UUID tenantId, UUID contactId, UUID actualOutboundDidId,
                         LocalDate usageDate) {
        return usageRepository
                .findByTenantIdAndContactIdAndDidIdAndUsageDate(
                        tenantId, contactId, actualOutboundDidId, usageDate)
                .map(VoiceBlastDailyUsage::getUsedCount)
                .orElse(0);
    }

    /**
     * Idempotent bucket-row creation. {@code INSERT … ON CONFLICT DO
     * NOTHING} is the race authority: concurrent creators converge on one
     * row, no exception is raised, and the caller's transaction is never
     * poisoned before the conditional reserve runs.
     */
    private void ensureBucketRow(UUID tenantId, UUID contactId, UUID didId,
                                 LocalDate usageDate) {
        usageRepository.insertBucketRow(tenantId, contactId, didId, usageDate);
    }
}
