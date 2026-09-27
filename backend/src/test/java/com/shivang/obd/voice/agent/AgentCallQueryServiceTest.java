package com.shivang.obd.voice.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.agent.dto.AgentActiveCallResponse;
import com.shivang.obd.voice.agent.dto.AgentCallHistoryResponse;
import com.shivang.obd.voice.call.CallDirection;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * VB-4A unit tests for the agent call queries. Active and historical
 * calls are derived from the canonical CallSession/CallLeg model; these
 * tests pin the derivation semantics (active statuses, ended-session
 * filter, tenant isolation) before the PostgreSQL suite proves them
 * against real data.
 */
class AgentCallQueryServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("77777777-0000-4000-8000-000000000001");
    private static final UUID AGENT_ID =
            UUID.fromString("eeeeeeee-0000-4000-8000-000000000001");

    private AgentRepository agentRepository;
    private CallLegRepository callLegRepository;
    private CallSessionRepository sessionRepository;
    private TenantRepository tenantRepository;
    private CurrentUserProvider currentUserProvider;
    private AgentCallQueryService service;

    private Agent agent;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        callLegRepository = mock(CallLegRepository.class);
        sessionRepository = mock(CallSessionRepository.class);
        tenantRepository = mock(TenantRepository.class);
        currentUserProvider = mock(CurrentUserProvider.class);

        service = new AgentCallQueryService(agentRepository, callLegRepository,
                sessionRepository, tenantRepository,
                mock(AuthorizationService.class), currentUserProvider);
        when(currentUserProvider.current())
                .thenReturn(Optional.of(new com.shivang.obd.security.AuthenticatedUser(
                        USER_ID, "user@example.com", null)));

        agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setTenantId(TENANT_A);
        agent.setDisplayName("Agent One");
        agent.setAdminStatus(AgentAdminStatus.ACTIVE);
        agent.setAvailability(AgentAvailability.AVAILABLE);

        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(AGENT_ID, TENANT_A))
                .thenReturn(Optional.of(agent));
        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    @Test
    @DisplayName("Q1: no active calls → empty list")
    void noActiveCalls() {
        when(callLegRepository
                .findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullOrderByInitiatedAtDesc(
                        AGENT_ID, CallLegType.AGENT, AgentSpecifications.ACTIVE_LEG_STATUSES))
                .thenReturn(List.of());

        assertThat(service.getActiveCalls(AGENT_ID).data()).isEmpty();
    }

    @Test
    @DisplayName("Q2: BRIDGED agent leg of a live session → one active call")
    void bridgedLegIsAnActiveCall() {
        CallSession session = session(CallSessionStatus.BRIDGED, null);
        CallLeg leg = leg(CallLegStatus.BRIDGED, session.getId(), null);
        when(callLegRepository
                .findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullOrderByInitiatedAtDesc(
                        AGENT_ID, CallLegType.AGENT, AgentSpecifications.ACTIVE_LEG_STATUSES))
                .thenReturn(List.of(leg));
        when(sessionRepository.findById(session.getId()))
                .thenReturn(Optional.of(session));

        List<AgentActiveCallResponse> calls = service.getActiveCalls(AGENT_ID).data();

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).callSessionId()).isEqualTo(session.getId());
        assertThat(calls.get(0).callLegId()).isEqualTo(leg.getId());
        assertThat(calls.get(0).legStatus()).isEqualTo("BRIDGED");
        assertThat(calls.get(0).remoteTarget()).isEqualTo("+15550001111");
    }

    @Test
    @DisplayName("Q3: leg of an ended session is excluded from active calls")
    void endedSessionExcludedFromActive() {
        CallSession ended = session(CallSessionStatus.COMPLETED, Instant.now());
        CallLeg leg = leg(CallLegStatus.ANSWERED, ended.getId(), null);
        when(callLegRepository
                .findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullOrderByInitiatedAtDesc(
                        AGENT_ID, CallLegType.AGENT, AgentSpecifications.ACTIVE_LEG_STATUSES))
                .thenReturn(List.of(leg));
        when(sessionRepository.findById(ended.getId()))
                .thenReturn(Optional.of(ended));

        assertThat(service.getActiveCalls(AGENT_ID).data()).isEmpty();
    }

    @Test
    @DisplayName("Q4: multiple active calls listed newest first")
    void multipleActiveCalls() {
        CallSession s1 = session(CallSessionStatus.BRIDGED, null);
        CallSession s2 = session(CallSessionStatus.CONNECTING_AGENT, null);
        CallLeg older = leg(CallLegStatus.ANSWERED, s1.getId(), null);
        CallLeg newer = leg(CallLegStatus.RINGING, s2.getId(), null);
        when(callLegRepository
                .findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullOrderByInitiatedAtDesc(
                        AGENT_ID, CallLegType.AGENT, AgentSpecifications.ACTIVE_LEG_STATUSES))
                .thenReturn(List.of(newer, older));
        when(sessionRepository.findById(s1.getId())).thenReturn(Optional.of(s1));
        when(sessionRepository.findById(s2.getId())).thenReturn(Optional.of(s2));

        List<AgentActiveCallResponse> calls = service.getActiveCalls(AGENT_ID).data();
        assertThat(calls).hasSize(2);
    }

    @Test
    @DisplayName("Q5: history returns terminal legs of ended sessions with timestamps")
    void historyReturnsTerminalLegs() {
        CallSession ended = session(CallSessionStatus.COMPLETED, Instant.now());
        CallLeg leg = leg(CallLegStatus.COMPLETED, ended.getId(), Instant.now());
        leg.setFailureCode("CALL_FAILED");
        when(callLegRepository.findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                AGENT_ID, CallLegType.AGENT, AgentSpecifications.HISTORY_LEG_STATUSES,
                PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(leg)));
        when(sessionRepository.findById(ended.getId()))
                .thenReturn(Optional.of(ended));

        List<AgentCallHistoryResponse> history =
                service.getCallHistory(AGENT_ID, 0, 20, null, null).data();

        assertThat(history).hasSize(1);
        assertThat(history.get(0).legStatus()).isEqualTo("COMPLETED");
        assertThat(history.get(0).endedAt()).isNotNull();
        assertThat(history.get(0).failureCode()).isEqualTo("CALL_FAILED");
    }

    @Test
    @DisplayName("Q6: terminal leg of a non-ended session is excluded from history")
    void terminalLegOfLiveSessionNotHistorical() {
        CallSession live = session(CallSessionStatus.BRIDGED, null);
        CallLeg leg = leg(CallLegStatus.COMPLETED, live.getId(), Instant.now());
        when(callLegRepository.findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                AGENT_ID, CallLegType.AGENT, AgentSpecifications.HISTORY_LEG_STATUSES,
                PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(leg)));
        when(sessionRepository.findById(live.getId())).thenReturn(Optional.of(live));

        assertThat(service.getCallHistory(AGENT_ID, 0, 20, null, null).data())
                .isEmpty();
    }

    @Test
    @DisplayName("Q7: cross-tenant agent lookup fails closed with 404")
    void crossTenantAgentFailsClosed() {
        UUID foreign = UUID.randomUUID();
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(foreign, TENANT_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getActiveCalls(foreign))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Q8: reseller scope cannot resolve an out-of-hierarchy agent")
    void resellerCannotResolveForeignAgent() {
        UUID resellerId = UUID.fromString("99999999-0000-4000-8000-000000000003");
        UUID otherTenant = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001");
        OrganizationContextHolder.setAuthenticated(USER_ID, null, resellerId);
        Agent foreignAgent = new Agent();
        foreignAgent.setId(UUID.randomUUID());
        foreignAgent.setTenantId(otherTenant);
        when(agentRepository.findByIdAndDeletedAtIsNull(foreignAgent.getId()))
                .thenReturn(Optional.of(foreignAgent));
        TenantEntity outside = new TenantEntity();
        outside.setId(otherTenant);
        when(tenantRepository.findAllByResellerIdAndStatus(
                resellerId, LifecycleStatus.ACTIVE))
                .thenReturn(List.of(outsideTenant()));

        assertThatThrownBy(() -> service.getActiveCalls(foreignAgent.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === helpers ===

    private CallSession session(CallSessionStatus status, Instant endedAt) {
        CallSession s = new CallSession();
        s.setId(UUID.randomUUID());
        s.setTenantId(TENANT_A);
        s.setStatus(status);
        s.setDirection(CallDirection.OUTBOUND);
        s.setDestinationNumber("+15550001111");
        s.setInitiatedAt(Instant.now().minusSeconds(300));
        s.setEndedAt(endedAt);
        return s;
    }

    private CallLeg leg(CallLegStatus status, UUID sessionId, Instant endedAt) {
        CallLeg l = new CallLeg();
        l.setId(UUID.randomUUID());
        l.setCallSessionId(sessionId);
        l.setLegType(CallLegType.AGENT);
        l.setDirection(CallDirection.OUTBOUND);
        l.setStatus(status);
        l.setInitiatedAt(Instant.now().minusSeconds(120));
        l.setAnsweredAt(Instant.now().minusSeconds(60));
        l.setEndedAt(endedAt);
        return l;
    }

    private TenantEntity outsideTenant() {
        TenantEntity t = new TenantEntity();
        t.setId(UUID.fromString("cccccccc-0000-4000-8000-000000000001"));
        t.setName("outside");
        t.setStatus(LifecycleStatus.ACTIVE);
        return t;
    }
}
