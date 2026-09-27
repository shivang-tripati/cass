package com.shivang.obd.voice.acd;

import com.shivang.obd.voice.queue.Queue;
import com.shivang.obd.voice.queue.QueueFoundationIntegrationSupport;
import com.shivang.obd.voice.queue.QueueRepository;
import com.shivang.obd.voice.queue.QueueStatus;
import com.shivang.obd.voice.queue.QueueWaitingCall;
import com.shivang.obd.voice.queue.QueueWaitingCallRepository;
import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-4C integration support: extends the VB-4B real-PostgreSQL harness
 * (Testcontainers, Flyway chain V1..V38, NOT_SUPPORTED + inTx pattern)
 * with queue/waiting-call seeding and the ACD services over real
 * repositories — including the real {@code AgentReservationService}
 * (advisory-lock reservation) and a real EntityManager.
 *
 * <p>Authorization is a no-op at this level (covered by unit/slice
 * tests); ACD itself never touches telephony collaborators.</p>
 */
public abstract class AcdIntegrationSupport
        extends QueueFoundationIntegrationSupport {

    @Autowired
    protected QueueRepository queueRepository;
    @Autowired
    protected QueueWaitingCallRepository waitingCallRepository;
    @Autowired
    protected com.shivang.obd.voice.agent.AgentReservationRepository reservationRepository;

    protected AcdService acdService;
    protected AcdOverflowService overflowService;

    /**
     * Builds the ACD services over real repositories + the REAL
     * {@link AgentReservationService} (VB-3 harness constructs it manually —
     * {@code @Service} beans are outside the {@code @DataJpaTest} slice),
     * so the advisory-lock reservation path runs for real.
     */
    protected void initAcdServices() {
        initReservationService(); // from AgentConnectIntegrationSupport → this.reservationService
        acdService = new AcdService(queueRepository, membershipRepository,
                waitingCallRepository, agentRepository, endpointRepository,
                reservationService, reservationRepository);
        overflowService = new AcdOverflowService(queueRepository, waitingCallRepository);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public UUID seedQueue(UUID tenantId, String name, QueueStatus status) {
        Queue queue = new Queue();
        queue.setTenantId(tenantId);
        queue.setName(name);
        queue.setStatus(status);
        return queueRepository.save(queue).getId();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public UUID seedWaitingCall(UUID tenantId, UUID queueId, Instant expiresAt) {
        QueueWaitingCall wc = new QueueWaitingCall();
        wc.setTenantId(tenantId);
        wc.setQueueId(queueId);
        wc.setCallSessionId(seedCallSession(tenantId));
        wc.setStatus(QueueWaitingCallStatus.WAITING);
        wc.setEnteredAt(Instant.now());
        wc.setExpiresAt(expiresAt);
        return waitingCallRepository.save(wc).getId();
    }

    /** Raw count of live WAITING rows for a queue. */
    public int waitingCount(UUID queueId) {
        return inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM queue_waiting_calls WHERE queue_id = :q "
                    + "AND status = 'WAITING' AND deleted_at IS NULL")
                    .setParameter("q", queueId).getSingleResult();
            return n.intValue();
        });
    }

    /** Raw count of live ASSIGNED rows for a queue. */
    public int assignedCount(UUID queueId) {
        return inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM queue_waiting_calls WHERE queue_id = :q "
                    + "AND status = 'ASSIGNED' AND deleted_at IS NULL")
                    .setParameter("q", queueId).getSingleResult();
            return n.intValue();
        });
    }

    /** Raw count of non-RELEASED reservations for an agent. */
    public int activeHolds(UUID agentId) {
        return inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM agent_reservations WHERE agent_id = :a "
                    + "AND status <> 'RELEASED' AND deleted_at IS NULL")
                    .setParameter("a", agentId).getSingleResult();
            return n.intValue();
        });
    }

    /** Clears all test ACD/queue/agent/call data in FK-safe order. */
    @Override
    public void cleanupAgentData() {
        inTx(() -> {
            // Circular FKs between waiting calls and reservations: break the
            // cycle by nulling the reference columns, then delete.
            entityManager.createNativeQuery(
                    "UPDATE queue_waiting_calls SET assigned_reservation_id = NULL, "
                    + "assigned_agent_id = NULL WHERE assigned_reservation_id IS NOT NULL")
                    .executeUpdate();
            entityManager.createNativeQuery(
                    "UPDATE agent_reservations SET waiting_call_id = NULL, queue_id = NULL "
                    + "WHERE waiting_call_id IS NOT NULL OR queue_id IS NOT NULL")
                    .executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM agent_reservations").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM queue_waiting_calls").executeUpdate();
            entityManager.createNativeQuery(
                    "UPDATE queues SET overflow_queue_id = NULL").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM queue_memberships").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM queues").executeUpdate();
            return null;
        });
        super.cleanupAgentData();
    }
}
