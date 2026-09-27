package com.shivang.obd.voice.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.voice.capacity.AgentConnectIntegrationSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-4F hardening: the database enforces "one live (non-RELEASED)
 * reservation per call session" (V40 partial unique index). The
 * reservation lifecycle resolves and releases holds by call_session_id,
 * so a second live hold for the same session would leak an agent slot
 * forever — the DB now makes that impossible.
 */
class AgentReservationUniquenessIntegrationTest extends AgentConnectIntegrationSupport {

    @BeforeEach
    void setUp() {
        initReservationService();
    }

    @AfterEach
    void tearDown() {
        cleanupAgentData();
    }

    @Test
    @DisplayName("UQ-1: second live hold for the same call session is rejected by the database")
    void secondLiveHoldForSameSessionRejected() {
        UUID tenantId = seedTenant("uq1");
        UUID agentA = seedAgent(tenantId, "uq1-a", AgentAdminStatus.ACTIVE,
                AgentAvailability.AVAILABLE, 5);
        UUID agentB = seedAgent(tenantId, "uq1-b", AgentAdminStatus.ACTIVE,
                AgentAvailability.AVAILABLE, 5);
        UUID sessionId = seedCallSession(tenantId);

        inTx(() -> {
            var first = reservationService.reserve(agentA, tenantId, sessionId, null);
            assertThat(first).isPresent();
            return null;
        });

        // A second reservation for the same session (even for a different
        // agent) must be impossible at the database level.
        assertThatThrownBy(() -> inTx(() -> {
            try {
                reservationService.reserve(agentB, tenantId, sessionId, null);
            } catch (RuntimeException e) {
                // Hibernate wraps the constraint violation — surface it.
                throw e;
            }
            return null;
        })).isInstanceOf(RuntimeException.class);

        // Exactly one live hold remains (the first), and the failed insert
        // left no partial state.
        inTx(() -> {
            assertThat(activeReservationCount(agentA)).isEqualTo(1);
            assertThat(activeReservationCount(agentB)).isZero();
            return null;
        });
    }

    @Test
    @DisplayName("UQ-2: released session can be reserved again (reuse after terminal release)")
    void releasedSessionCanBeReservedAgain() {
        UUID tenantId = seedTenant("uq2");
        UUID agentId = seedAgent(tenantId, "uq2-a", AgentAdminStatus.ACTIVE,
                AgentAvailability.AVAILABLE, 2);
        UUID sessionId = seedCallSession(tenantId);

        inTx(() -> {
            assertThat(reservationService.reserve(agentId, tenantId, sessionId, null)).isPresent();
            return null;
        });
        inTx(() -> {
            reservationService.releaseForCallSession(sessionId, "CALL_ENDED");
            return null;
        });

        // The prior hold is RELEASED — a new hold for the same session
        // (retry/redial scenario) is legitimate.
        inTx(() -> {
            var second = reservationService.reserve(agentId, tenantId, sessionId, null);
            assertThat(second).isPresent();
            assertThat(activeReservationCount(agentId)).isEqualTo(1);
            return null;
        });
    }
}
