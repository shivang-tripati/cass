package com.shivang.obd.voice.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reclaims stale agent reservations (VB-3), mirroring the VB-0
 * voice-channel reconciler: a JVM crash, process restart or lost FreeSWITCH
 * event must never strand an agent slot. Only RESERVED holds older than the
 * threshold are reclaimed — an ACTIVE hold belongs to a live bridged call
 * and is released by the hangup paths; a RESERVED hold has no agent answer
 * yet, and the no-answer timeout (60s, see ConnectByAgentService) already
 * guarantees origination cannot outlast this window, so 10 minutes is
 * conservatively safe.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentStaleReservationReconciler {

    /** RESERVED holds older than this are orphans (crash/lost event). */
    static final int STALE_THRESHOLD_SECONDS = 600;

    private final AgentReservationRepository reservationRepository;
    private final AgentRepository agentRepository;

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void reconcileStaleReservations() {
        var cutoff = java.time.Instant.now().minusSeconds(STALE_THRESHOLD_SECONDS);
        // Snapshot the affected agents BEFORE the bulk release so their
        // runtime availability signal can be restored afterwards (BUSY is
        // owned by the reservation lifecycle — a reclaimed hold must not
        // leave the agent stuck BUSY and locked out of VB-4E outbound calls).
        List<UUID> staleAgentIds = reservationRepository
                .findAgentIdsWithStaleReservations(cutoff);
        int reclaimed = reservationRepository.releaseStaleReservations(cutoff);
        if (reclaimed > 0) {
            log.info("Reconciled {} stale agent reservations", reclaimed);
            for (UUID agentId : staleAgentIds) {
                // Only agents with no remaining active hold return to AVAILABLE.
                if (reservationRepository.countActiveByAgentId(agentId) == 0) {
                    agentRepository.findById(agentId)
                            .filter(a -> a.getDeletedAt() == null)
                            .filter(a -> a.getAvailability() == AgentAvailability.BUSY)
                            .ifPresent(a -> {
                                a.setAvailability(AgentAvailability.AVAILABLE);
                                agentRepository.save(a);
                                log.info("Agent {} availability restored after stale reclaim", agentId);
                            });
                }
            }
        }
    }
}
