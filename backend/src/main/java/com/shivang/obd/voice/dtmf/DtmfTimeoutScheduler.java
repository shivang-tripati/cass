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
    /**
     * VB-6F: per-node IVR deadlines, scanned by this same poller.
     * <p>
     * A multi-level IVR has one wait window per node, and a new one is armed each
     * time the caller advances. Folding the scan into the existing 1-second
     * poller is what keeps VB-6F from adding a scheduler, a timer framework or a
     * second thread — the brief forbids all three, and the existing scan already
     * bounds how late a timeout can fire.
     * <p>
     * Ordered after the single-level scan so the proven VB-2 path is unaffected.
     */
    private final java.util.Optional<com.shivang.obd.voice.ivr.IvrStepRepository> ivrStepRepository;

    /** Scan interval: bound on how late a timeout can fire. */
    static final long SCAN_INTERVAL_MILLIS = 1000;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MILLIS)
    @Transactional
    public void enforceTimeouts() {
        List<DtmfInteraction> expired = interactionRepository.findExpired(Instant.now());
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

        ivrStepRepository.ifPresent(steps -> {
            List<com.shivang.obd.voice.ivr.IvrStep> overdue =
                    steps.findExpired(Instant.now());
            for (com.shivang.obd.voice.ivr.IvrStep step : overdue) {
                try {
                    dtmfCollectorTrigger.onDtmfTimeout(step.getCallSessionId());
                } catch (Exception e) {
                    // Same containment as above: one bad step must not block the
                    // rest of the scan, and the atomic claim makes a retry safe.
                    log.warn("IVR step timeout processing failed for session {}: {}",
                            step.getCallSessionId(), e.getMessage());
                }
            }
        });
    }
}
