package com.shivang.obd.voice.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.voice.capacity.AgentConnectIntegrationSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallLegRepository;

/**
 * VB-4F hardening: stale-reservation reconciliation on real PostgreSQL.
 * The bulk reclaim ({@code STALE_RECLAIM}) must restore the agent's runtime
 * availability signal (BUSY is owned by the reservation lifecycle — a
 * reclaimed hold must not leave the agent stuck BUSY, which would lock it
 * out of VB-4E outbound calls), and running the reconciler twice must not
 * corrupt state (idempotency).
 */
class AgentStaleReconcilerIntegrationTest extends AgentConnectIntegrationSupport {

    @Autowired
    private com.shivang.obd.voice.call.CallSessionRepository callSessionRepository;

    private AgentStaleReservationReconciler reconciler;

    @BeforeEach
    void setUp() {
        initReservationService();
        reconciler = new AgentStaleReservationReconciler(
                reservationRepository, agentRepository);
    }

    @AfterEach
    void tearDown() {
        cleanupAgentData();
    }

    @Test
    @DisplayName("SR-1: stale RESERVED hold reclaimed → hold RELEASED and agent availability restored to AVAILABLE")
    void staleReclaimRestoresAvailability() {
        UUID tenantId = seedTenant("sr1");
        UUID agentId = seedAgent(tenantId, "sr1-agent",
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, 1);
        UUID sessionId = seedCallSession(tenantId);

        // A stale hold: reserved normally, availability flipped to BUSY.
        inTx(() -> {
            reservationService.reserve(agentId, tenantId, sessionId, null);
            return null;
        });
        inTx(() -> {
            String avail = (String) entityManager.createNativeQuery(
                    "SELECT availability FROM agents WHERE id = :a")
                    .setParameter("a", agentId).getSingleResult();
            assertThat(avail).isEqualTo("BUSY");
            return null;
        });

        // Age the reservation past the stale threshold directly.
        inTx(() -> {
            entityManager.createNativeQuery(
                    "UPDATE agent_reservations SET reserved_at = :old "
                    + "WHERE agent_id = :a AND status = 'RESERVED'")
                    .setParameter("old", Instant.now().minusSeconds(
                            AgentStaleReservationReconciler.STALE_THRESHOLD_SECONDS + 60))
                    .setParameter("a", agentId)
                    .executeUpdate();
            return null;
        });

        inTx(() -> {
            reconciler.reconcileStaleReservations();
            return null;
        });

        inTx(() -> {
            assertThat(activeReservationCount(agentId)).isZero();
            String avail = (String) entityManager.createNativeQuery(
                    "SELECT availability FROM agents WHERE id = :a")
                    .setParameter("a", agentId).getSingleResult();
            assertThat(avail).isEqualTo("AVAILABLE");
            Number reason = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM agent_reservations WHERE agent_id = :a "
                    + "AND release_reason = 'STALE_RECLAIM'")
                    .setParameter("a", agentId).getSingleResult();
            assertThat(reason.intValue()).isEqualTo(1);
            return null;
        });
    }

    @Test
    @DisplayName("SR-2: reconciler is idempotent — second run is a no-op, availability stays AVAILABLE")
    void reconcilerIsIdempotent() {
        UUID tenantId = seedTenant("sr2");
        UUID agentId = seedAgent(tenantId, "sr2-agent",
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, 1);
        UUID sessionId = seedCallSession(tenantId);

        inTx(() -> {
            reservationService.reserve(agentId, tenantId, sessionId, null);
            return null;
        });
        inTx(() -> {
            entityManager.createNativeQuery(
                    "UPDATE agent_reservations SET reserved_at = :old "
                    + "WHERE agent_id = :a AND status = 'RESERVED'")
                    .setParameter("old", Instant.now().minusSeconds(
                            AgentStaleReservationReconciler.STALE_THRESHOLD_SECONDS + 60))
                    .setParameter("a", agentId)
                    .executeUpdate();
            return null;
        });        inTx(() -> {
            reconciler.reconcileStaleReservations();
            return null;
        });
        inTx(() -> {
            reconciler.reconcileStaleReservations();
            return null;
        });

        inTx(() -> {
            assertThat(activeReservationCount(agentId)).isZero();
            String avail = (String) entityManager.createNativeQuery(
                    "SELECT availability FROM agents WHERE id = :a")
                    .setParameter("a", agentId).getSingleResult();
            assertThat(avail).isEqualTo("AVAILABLE");
            Number released = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM agent_reservations WHERE agent_id = :a "
                    + "AND status = 'RELEASED'")
                    .setParameter("a", agentId).getSingleResult();
            assertThat(released.intValue()).isEqualTo(1); // no duplicate releases
            return null;
        });
    }

    @Test
    @DisplayName("SR-3: agent with a SECOND live hold keeps the remaining hold — availability stays BUSY, only the stale one reclaimed")
    void agentWithLiveHoldKeepsBusy() {
        UUID tenantId = seedTenant("sr3");
        // capacity 2: one stale RESERVED hold + one fresh RESERVED hold
        UUID agentId = seedAgent(tenantId, "sr3-agent",
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, 2);

        UUID staleSession = seedCallSession(tenantId);
        UUID freshSession = seedCallSession(tenantId);

        inTx(() -> {
            reservationService.reserve(agentId, tenantId, staleSession, null);
            reservationService.reserve(agentId, tenantId, freshSession, null);
            return null;
        });
        inTx(() -> {
            entityManager.createNativeQuery(
                    "UPDATE agent_reservations SET reserved_at = :old "
                    + "WHERE call_session_id = :s AND status = 'RESERVED'")
                    .setParameter("old", Instant.now().minusSeconds(
                            AgentStaleReservationReconciler.STALE_THRESHOLD_SECONDS + 60))
                    .setParameter("s", staleSession)
                    .executeUpdate();
            return null;
        });

        inTx(() -> {
            reconciler.reconcileStaleReservations();
            return null;
        });

        inTx(() -> {
            // Only the stale hold reclaimed; the fresh one survives.
            assertThat(activeReservationCount(agentId)).isEqualTo(1);
            String avail = (String) entityManager.createNativeQuery(
                    "SELECT availability FROM agents WHERE id = :a")
                    .setParameter("a", agentId).getSingleResult();
            assertThat(avail).isEqualTo("BUSY");
            return null;
        });
    }
}
