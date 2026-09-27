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
}
