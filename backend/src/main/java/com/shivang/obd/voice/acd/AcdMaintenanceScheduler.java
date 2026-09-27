package com.shivang.obd.voice.acd;

import com.shivang.obd.voice.agent.AgentAvailability;
import com.shivang.obd.voice.agent.AgentRepository;
import com.shivang.obd.voice.agent.AgentReservationRepository;
import com.shivang.obd.voice.queue.QueueWaitingCallRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACD maintenance sweeps (VB-4C) — poll-based like every scheduler in
 * this codebase (@Scheduled convention; no new scheduler framework).
 * Both sweeps are idempotent: conditional UPDATEs mean a repeated run
 * has nothing left to change.
 *
 * <p><b>Reservation expiry:</b> ACD holds carry {@code expires_at} (TTL
 * from {@link AcdService#RESERVATION_TTL}); if the consuming VB-4D flow
 * never claims the assignment, the hold is released
 * ({@code ACD_EXPIRED}) and the waiting call returns to WAITING so it is
 * not stranded on a dead assignment. The agents whose holds expired have
 * their runtime availability flipped back to AVAILABLE — the reservation
 * lifecycle owns the BUSY/AVAILABLE signal (VB-3 rule). VB-3 holds (no
 * expiry) are untouched — the VB-3 stale reconciler owns those.</p>
 *
 * <p><b>Queue timeout:</b> executes VB-4B's persisted
 * {@code max_wait_seconds} via the waiting row's {@code expires_at}
 * snapshot: WAITING past expiry → ABANDONED. ASSIGNED calls are never
 * abandoned by this sweep — an assigned call is being connected; only
 * the expired-reservation sweep above may move it back to WAITING
 * first, after which the next timeout pass applies.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AcdMaintenanceScheduler {

    private final AgentReservationRepository reservationRepository;
    private final AgentRepository agentRepository;
    private final QueueWaitingCallRepository waitingCallRepository;

    @Scheduled(fixedDelay = 15000)
    @Transactional
    public void sweep() {
        Instant now = Instant.now();

        // 1. Release expired ACD holds (idempotent conditional UPDATE).
        int expired = reservationRepository.releaseExpiredReservations(
                now, AcdReasons.EXPIRED);
        if (expired > 0) {
            // 2. Unwind their waiting-call assignments → back to WAITING.
            Instant since = now.minusSeconds(120);
            List<UUID> released = reservationRepository
                    .findIdsReleasedWithReasonSince(AcdReasons.EXPIRED, since);
            for (UUID reservationId : released) {
                int reverted = waitingCallRepository.returnToWaitingByReservation(
                        reservationId);
                if (reverted > 0) {
                    log.info("ACD reservation expired (reservation={}) — "
                            + "waiting call returned to queue", reservationId);
                }
            }
            // 3. Restore the runtime availability signal for the freed agents.
            List<UUID> agentIds = reservationRepository
                    .findAgentIdsReleasedWithReasonSince(AcdReasons.EXPIRED, since);
            for (UUID agentId : agentIds) {
                agentRepository.findById(agentId)
                        .filter(a -> a.getDeletedAt() == null)
                        .filter(a -> a.getAvailability() == AgentAvailability.BUSY)
                        .ifPresent(a -> {
                            a.setAvailability(AgentAvailability.AVAILABLE);
                            agentRepository.save(a);
                        });
            }
        }

        // 4. Queue timeout: WAITING past the persisted wait budget → ABANDONED.
        int abandoned = waitingCallRepository.abandonExpiredWaitingCalls(now);
        if (abandoned > 0) {
            log.info("Queue timeout: {} waiting call(s) abandoned", abandoned);
        }
    }
}
