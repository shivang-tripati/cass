package com.shivang.obd.voice.dtmf;

import java.time.Instant;
import java.util.List;
import com.shivang.obd.voice.media.DtmfCollectorTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic DTMF collection timeout enforcement (VB-2).
 * <p>
 * Follows the project's existing {@code @Scheduled(fixedDelay)} poller
 * convention (see {@code EslEventScheduler},
 * {@code VoiceCapacityServiceImpl#reconcileReservations}) — no new scheduler
 * framework. The authoritative timeout source is the persisted
 * {@code expires_at} on {@link DtmfInteraction}, computed once at interaction
 * creation: deterministic, correlated to the specific interaction, and safe
 * against re-delivery (a stale timeout from an earlier interaction cannot
 * terminate a later one — each interaction carries its own deadline).
 * <p>
 * Finalization is delegated to {@link DtmfResultService}, whose atomic claim
 * makes duplicate scans and poller/event-thread races no-ops. A TIMEOUT
 * result hangs the call up through the media boundary; the CHANNEL_HANGUP
 * event remains the single authoritative reservation release path.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DtmfTimeoutScheduler {

    private final DtmfInteractionRepository interactionRepository;
    private final DtmfCollectorTrigger dtmfCollectorTrigger;

    /** Scan interval: bound on how late a timeout can fire. */
    static final long SCAN_INTERVAL_MILLIS = 1000;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MILLIS)
    @Transactional
    public void enforceTimeouts() {
        List<DtmfInteraction> expired = interactionRepository.findExpired(Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        log.debug("DTMF timeout scan: {} expired interaction(s)", expired.size());
        for (DtmfInteraction interaction : expired) {
            try {
                dtmfCollectorTrigger.onDtmfTimeout(interaction.getCallSessionId());
            } catch (Exception e) {
                // One bad interaction must not block the rest of the scan;
                // the next scan retries (claim makes re-processing safe).
                log.warn("DTMF timeout processing failed for session {}: {}",
                        interaction.getCallSessionId(), e.getMessage());
            }
        }
    }
}
