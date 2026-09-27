package com.shivang.obd.voice.agent;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic agent reservation (VB-3).
 * <p>
 * Concurrency safety comes from the same PostgreSQL mechanism VB-0 uses for
 * gateway channels: a transaction-scoped advisory lock serializes
 * reserve/scan for one agent, and the release path is an idempotent
 * conditional UPDATE. No Redis, no second locking infrastructure.
 * <p>
 * The reservation hold (a persisted {@link AgentReservation} in RESERVED)
 * counts against {@code agents.max_concurrent_calls}; exactly
 * {@code maxConcurrentCalls} non-RELEASED reservations can ever exist for
 * an agent, proven under concurrency by the integration test suite.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentReservationService {

    private final AgentRepository agentRepository;
    private final AgentReservationRepository reservationRepository;
    private final jakarta.persistence.EntityManager entityManager;

    /** Advisory lock base — disjoint from VB-0 channel (0x1…) / CPS (0x2…) bases. */
    private static final long AGENT_LOCK_BASE = 0x300000000L;

    private static long agentLockId(UUID agentId) {
        return AGENT_LOCK_BASE + agentId.hashCode();
    }

    /**
     * Atomically reserves a concurrency slot on the agent for the call.
     * Fails closed when the agent does not exist in the tenant, is not
     * administratively active, or is at its concurrency limit.
     *
     * @return the created reservation, or empty when the hold could not be
     *         taken (agent unavailable/at capacity — caller may try the
     *         next candidate)
     */
    @Transactional
    public java.util.Optional<AgentReservation> reserve(
            UUID agentId, UUID tenantId, UUID callSessionId, UUID attemptId) {

        Agent agent = agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agentId, tenantId)
                .orElse(null);
        if (agent == null) {
            log.debug("Agent {} not found for tenant {} — reservation refused", agentId, tenantId);
            return java.util.Optional.empty();
        }
        if (agent.getAdminStatus() != AgentAdminStatus.ACTIVE) {
            log.debug("Agent {} admin status {} — reservation refused",
                    agentId, agent.getAdminStatus());
            return java.util.Optional.empty();
        }

        // Transaction-scoped advisory lock — released automatically at commit
        // or rollback, exactly like VB-0's channel/CPS locks (no manual unlock:
        // pg_advisory_xact_unlock does not exist in PostgreSQL; transaction-
        // scoped locks are auto-released — see VB-4E defect report).
        long lockId = agentLockId(agentId);
        var lockQuery = entityManager.createNativeQuery(
                "SELECT pg_try_advisory_xact_lock(CAST(:lockId AS bigint))");
        lockQuery.setParameter("lockId", lockId);
        Boolean locked = (Boolean) lockQuery.getSingleResult();
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("Agent {} reservation lock contended — refusing", agentId);
            return java.util.Optional.empty();
        }

        int active = reservationRepository.countActiveByAgentId(agentId);
        if (active >= agent.getMaxConcurrentCalls()) {
            log.debug("Agent {} at concurrency limit ({}/{}) — reservation refused",
                    agentId, active, agent.getMaxConcurrentCalls());
            return java.util.Optional.empty();
        }

        AgentReservation reservation = new AgentReservation();
        reservation.setAgentId(agentId);
        reservation.setTenantId(tenantId);
        reservation.setCallSessionId(callSessionId);
        reservation.setAttemptId(attemptId);
        reservation.setStatus(AgentReservationStatus.RESERVED);
        reservation.setReservedAt(Instant.now());
        reservationRepository.save(reservation);

        log.info("Agent reservation created (agent={}, callSession={}, tenant={})",
                agentId, callSessionId, tenantId);
        markAvailability(agentId, AgentAvailability.BUSY);
        return java.util.Optional.of(reservation);
    }

    /**
     * Marks the agent BUSY/AVAILABLE from reservation lifecycle transitions.
     * Best-effort runtime signal: the selection query additionally filters on
     * live reservation counts, so a missed flip can never leak a call to a
     * fully-engaged agent — it only degrades the ordering heuristic.
     */
    private void markAvailability(UUID agentId, AgentAvailability availability) {
        agentRepository.findById(agentId)
                .filter(a -> a.getDeletedAt() == null)
                .ifPresent(a -> {
                    a.setAvailability(availability);
                    agentRepository.save(a);
                });
    }

    /**
     * Releases the active reservation for a call session. Idempotent:
     * releasing an already-RELEASED (or non-existent) reservation is a
     * no-op that never errors and never produces negative usage.
     *
     * @return true if this call released the hold (first release wins)
     */
    @Transactional
    public boolean releaseForCallSession(UUID callSessionId, String reason) {
        var reservationOpt = reservationRepository.findByCallSessionIdAndDeletedAtIsNull(callSessionId);
        if (reservationOpt.isEmpty()) {
            return false; // Nothing to release (no reservation, or released already)
        }
        UUID agentId = reservationOpt.get().getAgentId();
        int updated = reservationRepository.releaseByCallSession(callSessionId, reason);
        if (updated > 0) {
            log.info("Agent reservation released (callSession={}, reason={})", callSessionId, reason);
            markAvailability(agentId, AgentAvailability.AVAILABLE);
        }
        return updated > 0;
    }

    /** Marks the reservation for a call as ACTIVE (agent answered). Idempotent. */
    @Transactional
    public void markActiveForCallSession(UUID callSessionId) {
        reservationRepository.findByCallSessionIdAndDeletedAtIsNull(callSessionId)
                .filter(r -> r.getStatus() == AgentReservationStatus.RESERVED)
                .ifPresent(r -> reservationRepository.promoteToActive(r.getId()));
    }

    /**
     * Attaches the agent {@code callLegId} to the active reservation for a
     * call session (after the agent leg row is created). No-op when there is
     * no reservation (defensive — attach always follows a successful reserve).
     */
    @Transactional
    public void attachLeg(UUID callLegId, UUID callSessionId) {
        reservationRepository.findByCallSessionIdAndDeletedAtIsNull(callSessionId)
                .ifPresent(r -> {
                    r.setCallLegId(callLegId);
                    reservationRepository.save(r);
                });
    }
}
