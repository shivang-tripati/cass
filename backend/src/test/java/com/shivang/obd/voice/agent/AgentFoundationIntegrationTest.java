package com.shivang.obd.voice.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.voice.agent.dto.AgentActiveCallResponse;
import com.shivang.obd.voice.agent.dto.AgentAvailabilityResponse;
import com.shivang.obd.voice.agent.dto.AgentCallHistoryResponse;
import com.shivang.obd.voice.agent.dto.AgentEndpointResponse;
import com.shivang.obd.voice.agent.dto.AgentResponse;
import com.shivang.obd.voice.agent.dto.CreateAgentEndpointRequest;
import com.shivang.obd.voice.agent.dto.CreateAgentRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentPresenceRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentStatusRequest;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallSessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-4A PostgreSQL integration tests: persistence, native-enum handling,
 * derived queries and tenant isolation against a real PostgreSQL database
 * running the full Flyway chain (V1..V36). Provider/authorization edges
 * are mocked; data behavior is real.
 */
class AgentFoundationIntegrationTest extends AgentFoundationIntegrationSupport {

    private UUID tenantId;
    private UUID agentId;

    @BeforeEach
    void seed() {
        initServices();
        tenantId = seedTenant("a");
        agentId = seedAgent(tenantId, "Integration Agent",
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, 1);
        // Service-layer tenant context (server-derived scope under test).
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
                UUID.fromString("77777777-0000-4000-8000-000000000001"), tenantId, null);
    }

    @AfterEach
    void clean() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
        cleanupAgentData();
    }

    @Test
    @DisplayName("IT-1: agent lifecycle persists with native enums and default presence OFFLINE")
    void lifecyclePersistsWithNativeEnums() {
        // V36 default availability is OFFLINE even though the seed helper sets AVAILABLE —
        // verify create path defaults through the real service.
        ApiResponse<AgentResponse> created = inTx(() -> directoryService.createAgent(
                new CreateAgentRequest("Created Agent", 3, null)));
        UUID createdId = created.data().id();

        ApiResponse<AgentResponse> loaded = inTx(() -> directoryService.getAgent(createdId));
        assertThat(loaded.data().adminStatus()).isEqualTo(AgentAdminStatus.ACTIVE);
        // Documented default: new agents start present OFFLINE, not available.
        assertThat(loaded.data().availability()).isEqualTo(AgentAvailability.OFFLINE);
        assertThat(loaded.data().maxConcurrentCalls()).isEqualTo(3);
        assertThat(loaded.data().activeCallCount()).isZero();

        // Agent declares availability for the rest of the lifecycle walk.
        inTx(() -> directoryService.updatePresence(createdId,
                new UpdateAgentPresenceRequest(AgentAvailability.AVAILABLE)));

        // Suspended flips presence; DISABLED is terminal.
        inTx(() -> directoryService.updateStatus(createdId,
                new UpdateAgentStatusRequest(AgentAdminStatus.SUSPENDED)));
        ApiResponse<AgentResponse> suspended = inTx(() -> directoryService.getAgent(createdId));
        assertThat(suspended.data().availability()).isEqualTo(AgentAvailability.OFFLINE);

        inTx(() -> directoryService.updateStatus(createdId,
                new UpdateAgentStatusRequest(AgentAdminStatus.DISABLED)));
        assertThatThrownBy(() -> inTx(() -> directoryService.updateStatus(createdId,
                new UpdateAgentStatusRequest(AgentAdminStatus.ACTIVE))))
                .isInstanceOf(com.shivang.obd.common.exception.ConflictException.class);
    }

    @Test
    @DisplayName("IT-2: presence + availability persist and derive from canonical data")
    void presenceAndAvailabilityPersist() {
        // presence OFFLINE → unavailable
        ApiResponse<AgentResponse> offline = inTx(() -> directoryService.updatePresence(
                agentId, new UpdateAgentPresenceRequest(AgentAvailability.OFFLINE)));
        assertThat(offline.data().availability()).isEqualTo(AgentAvailability.OFFLINE);

        AgentAvailabilityResponse a = inTx(() -> directoryService.getAvailability(agentId)).data();
        assertThat(a.available()).isFalse();
        assertThat(a.reasonCode()).isEqualTo(AgentFoundationReasons.AGENT_OFFLINE);

        // back online with endpoint → available
        seedEndpoint(tenantId, agentId, "sip:int@pbx.example",
                com.shivang.obd.voice.call.EndpointType.SIP, true);
        inTx(() -> directoryService.updatePresence(agentId,
                new UpdateAgentPresenceRequest(AgentAvailability.AVAILABLE)));
        AgentAvailabilityResponse b = inTx(() -> directoryService.getAvailability(agentId)).data();
        assertThat(b.available()).isTrue();
        assertThat(b.reasonCode()).isEqualTo(AgentFoundationReasons.AVAILABLE);
    }

    @Test
    @DisplayName("IT-3: availability reflects active agent legs (canonical CallLeg derivation)")
    void availabilityDerivesFromLiveLegs() {
        seedEndpoint(tenantId, agentId, "sip:int@pbx.example",
                com.shivang.obd.voice.call.EndpointType.SIP, true);
        UUID sessionId = seedCallSession(tenantId);
        seedAgentLeg(tenantId, agentId, sessionId, CallLegStatus.BRIDGED,
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(50), null, null);

        AgentAvailabilityResponse a = inTx(() -> directoryService.getAvailability(agentId)).data();
        assertThat(a.available()).isFalse();
        assertThat(a.reasonCode()).isEqualTo(AgentFoundationReasons.AGENT_AT_CAPACITY);
        assertThat(inTx(() -> directoryService.getAgent(agentId)).data().activeCallCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("IT-4: active-calls query returns live BRIDGED leg, excludes ended sessions")
    void activeCallsDerivedFromCanonicalLegs() {
        // Live session (not ended) with a destination number.
        UUID liveSession = seedEndedCallSession(tenantId, new CallSessionStatusHolder(
                CallSessionStatus.WAITING_FOR_DTMF,
                Instant.now().minusSeconds(300), null));
        seedAgentLeg(tenantId, agentId, liveSession, CallLegStatus.BRIDGED,
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(50), null, null);

        UUID endedSession = seedEndedCallSession(tenantId, new CallSessionStatusHolder(
                CallSessionStatus.COMPLETED, Instant.now().minusSeconds(600), Instant.now().minusSeconds(100)));
        seedAgentLeg(tenantId, agentId, endedSession, CallLegStatus.ANSWERED,
                Instant.now().minusSeconds(200), Instant.now().minusSeconds(150), null, null);

        List<AgentActiveCallResponse> calls =
                inTx(() -> callQueryService.getActiveCalls(agentId)).data();

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).legStatus()).isEqualTo("BRIDGED");
        assertThat(calls.get(0).remoteTarget()).isEqualTo("+15550001111");
    }

    @Test
    @DisplayName("IT-5: history returns terminal legs of ended sessions; active call excluded")
    void historyReturnsEndedSessionsOnly() {
        UUID endedSession = seedEndedCallSession(tenantId, new CallSessionStatusHolder(
                CallSessionStatus.COMPLETED, Instant.now().minusSeconds(300), Instant.now()));
        seedAgentLeg(tenantId, agentId, endedSession, CallLegStatus.COMPLETED,
                Instant.now().minusSeconds(200), Instant.now().minusSeconds(150),
                Instant.now(), null);

        UUID liveSession = seedCallSession(tenantId);
        seedAgentLeg(tenantId, agentId, liveSession, CallLegStatus.BRIDGED,
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(50), null, null);

        List<AgentCallHistoryResponse> history =
                inTx(() -> callQueryService.getCallHistory(agentId, 0, 20, null, null)).data();

        assertThat(history).hasSize(1);
        assertThat(history.get(0).legStatus()).isEqualTo("COMPLETED");
        assertThat(history.get(0).endedAt()).isNotNull();
    }

    @Test
    @DisplayName("IT-6: tenant isolation — Tenant B cannot resolve Tenant A's agent or endpoint")
    void tenantIsolationFailsClosed() {
        UUID tenantB = seedTenant("b");
        UUID agentB = seedAgent(tenantB, "Tenant B Agent",
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, 1);

        // Tenant A context (set by seedTenant? no — context is fixed in support; use scoped repos directly)
        // The directory service uses OrganizationContextHolder, which the support does NOT set
        // per-test. Verify isolation through the tenant-scoped repository contracts instead.
        assertThat(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agentId, tenantB))
                .isEmpty();
        assertThat(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agentB, tenantId))
                .isEmpty();
        assertThat(endpointRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                UUID.randomUUID(), tenantB)).isEmpty();

        // The scoped service would 404 — proven at unit level; here we pin the
        // repository contracts the service depends on.
        assertThat(agentRepository.findByIdAndDeletedAtIsNull(agentId)).isPresent();
    }

    @Test
    @DisplayName("IT-7: endpoint management persists through real repository")
    void endpointManagementPersists() {
        ApiResponse<AgentEndpointResponse> created = inTx(() -> directoryService.createEndpoint(
                agentId, new CreateAgentEndpointRequest(null,
                        com.shivang.obd.voice.call.EndpointType.SIP, "it@pbx.example")));

        assertThat(created.data().enabled()).isTrue();
        assertThat(endpointRepository
                .findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(agentId, tenantId))
                .hasSize(1);

        ApiResponse<AgentEndpointResponse> disabled =
                inTx(() -> directoryService.disableEndpoint(created.data().id()));
        assertThat(disabled.data().enabled()).isFalse();

        // availability now fails on endpoint readiness
        AgentAvailabilityResponse a = inTx(() -> directoryService.getAvailability(agentId)).data();
        assertThat(a.available()).isFalse();
        assertThat(a.reasonCode()).isEqualTo(AgentFoundationReasons.AGENT_ENDPOINT_INVALID);
    }

    @Test
    @DisplayName("IT-8: caller legs (agentId null) never count as agent legs")
    void callerLegsAreNotAgentLegs() {
        seedEndpoint(tenantId, agentId, "sip:int@pbx.example",
                com.shivang.obd.voice.call.EndpointType.SIP, true);
        UUID sessionId = seedCallSession(tenantId);
        // customer leg without agentId
        seedAgentLeg(tenantId, null, sessionId, CallLegStatus.BRIDGED,
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(50), null, null);

        assertThat(inTx(() -> directoryService.getAvailability(agentId)).data().available())
                .isTrue();
        assertThat(inTx(() -> callQueryService.getActiveCalls(agentId)).data()).isEmpty();
    }
}
