package com.shivang.obd.voice.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.agent.dto.AgentAvailabilityResponse;
import com.shivang.obd.voice.agent.dto.AgentResponse;
import com.shivang.obd.voice.agent.dto.CreateAgentEndpointRequest;
import com.shivang.obd.voice.agent.dto.CreateAgentRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentEndpointRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentPresenceRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentStatusRequest;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * VB-4A unit tests: agent lifecycle, presence, availability and endpoint
 * management at the service boundary. Tenant isolation and capability
 * enforcement are exercised directly (server-derived scope + fail-closed
 * 404s); persistence-level behavior is proven by the PostgreSQL suite.
 */
class AgentDirectoryServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("77777777-0000-4000-8000-000000000001");

    private AgentRepository agentRepository;
    private AgentEndpointRepository endpointRepository;
    private AgentReservationRepository reservationRepository;
    private CallLegRepository callLegRepository;
    private TenantRepository tenantRepository;
    private AuthorizationService authorizationService;
    private CurrentUserProvider currentUserProvider;
    private AgentDirectoryService service;

    private Agent agent;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        endpointRepository = mock(AgentEndpointRepository.class);
        reservationRepository = mock(AgentReservationRepository.class);
        callLegRepository = mock(CallLegRepository.class);
        tenantRepository = mock(TenantRepository.class);
        authorizationService = mock(AuthorizationService.class);
        currentUserProvider = mock(CurrentUserProvider.class);

        service = new AgentDirectoryService(agentRepository, endpointRepository,
                reservationRepository, callLegRepository, tenantRepository,
                authorizationService, currentUserProvider);

        agent = new Agent();
        agent.setId(UUID.fromString("eeeeeeee-0000-4000-8000-000000000001"));
        agent.setTenantId(TENANT_A);
        agent.setDisplayName("Agent One");
        agent.setAdminStatus(AgentAdminStatus.ACTIVE);
        agent.setAvailability(AgentAvailability.AVAILABLE);
        agent.setMaxConcurrentCalls(1);

        when(currentUserProvider.current())
                .thenReturn(Optional.of(new com.shivang.obd.security.AuthenticatedUser(
                        USER_ID, "user@example.com", null)));
        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
        when(tenantRepository.findByIdAndDeletedAtIsNull(TENANT_A))
                .thenReturn(Optional.of(activeTenant(TENANT_A)));
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    // === lifecycle ===

    @Test
    @DisplayName("L1: create agent → ACTIVE + OFFLINE defaults, tenant from context")
    void createAgentAppliesDefaults() {
        var response = service.createAgent(new CreateAgentRequest("New Agent", null, null));

        assertThat(response.data().adminStatus()).isEqualTo(AgentAdminStatus.ACTIVE);
        assertThat(response.data().availability()).isEqualTo(AgentAvailability.OFFLINE);
        assertThat(response.data().maxConcurrentCalls()).isEqualTo(1);
        assertThat(response.data().tenantId()).isEqualTo(TENANT_A);
    }

    @Test
    @DisplayName("L2: get agent → tenant-scoped lookup, 404 for foreign agent")
    void getAgentFailsClosedAcrossTenants() {
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agent.getId(), TENANT_A))
                .thenReturn(Optional.of(agent));

        var response = service.getAgent(agent.getId());
        assertThat(response.data().id()).isEqualTo(agent.getId());

        // The tenant-scoped query misses → same 404 as nonexistent.
        assertThatThrownBy(() -> service.getAgent(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(agentRepository, org.mockito.Mockito.atLeastOnce())
                .findByIdAndTenantIdAndDeletedAtIsNull(agent.getId(), TENANT_A);
    }

    @Test
    @DisplayName("L3: reseller scope loads the row and resolves hierarchy visibility")
    void resellerScopeResolvesHierarchyVisibility() {
        UUID resellerId = UUID.fromString("99999999-0000-4000-8000-000000000001");
        OrganizationContextHolder.setAuthenticated(USER_ID, null, resellerId);
        when(agentRepository.findByIdAndDeletedAtIsNull(agent.getId()))
                .thenReturn(Optional.of(agent));
        when(tenantRepository.findAllByResellerIdAndStatus(
                resellerId, LifecycleStatus.ACTIVE))
                .thenReturn(List.of(activeTenant(TENANT_A)));
        when(callLegRepository.countByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                any(), any(), any())).thenReturn(0);

        var response = service.getAgent(agent.getId());
        assertThat(response.data().id()).isEqualTo(agent.getId());
    }

    @Test
    @DisplayName("L4: reseller scope rejects out-of-hierarchy agent with 404")
    void resellerScopeRejectsForeignAgent() {
        UUID resellerId = UUID.fromString("99999999-0000-4000-8000-000000000002");
        UUID otherTenant = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001");
        OrganizationContextHolder.setAuthenticated(USER_ID, null, resellerId);
        Agent foreign = agentOf(otherTenant);
        when(agentRepository.findByIdAndDeletedAtIsNull(foreign.getId()))
                .thenReturn(Optional.of(foreign));
        when(tenantRepository.findAllByResellerIdAndStatus(
                resellerId, LifecycleStatus.ACTIVE))
                .thenReturn(List.of(activeTenant(TENANT_A))); // foreign tenant not in hierarchy

        assertThatThrownBy(() -> service.getAgent(foreign.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("L5: update agent changes mutable fields only")
    void updateAgentChangesMutableFields() {
        stubTenantScopedAgent();
        when(callLegRepository.countByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                any(), any(), any())).thenReturn(0);

        var response = service.updateAgent(agent.getId(),
                new UpdateAgentRequest("Renamed", 5, null));

        assertThat(response.data().displayName()).isEqualTo("Renamed");
        assertThat(response.data().maxConcurrentCalls()).isEqualTo(5);
        assertThat(response.data().adminStatus()).isEqualTo(AgentAdminStatus.ACTIVE);
    }

    @Test
    @DisplayName("L6: update rejects maxConcurrentCalls < 1")
    void updateRejectsInvalidConcurrency() {
        stubTenantScopedAgent();

        assertThatThrownBy(() -> service.updateAgent(agent.getId(),
                new UpdateAgentRequest(null, 0, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("L7: ACTIVE→SUSPENDED flips presence OFFLINE")
    void suspendFlipsPresenceOffline() {
        stubTenantScopedAgent();

        var response = service.updateStatus(agent.getId(),
                new UpdateAgentStatusRequest(AgentAdminStatus.SUSPENDED));

        assertThat(response.data().adminStatus()).isEqualTo(AgentAdminStatus.SUSPENDED);
        assertThat(response.data().availability()).isEqualTo(AgentAvailability.OFFLINE);
    }

    @Test
    @DisplayName("L8: SUSPENDED→ACTIVE keeps presence unchanged")
    void reactivateKeepsPresence() {
        agent.setAdminStatus(AgentAdminStatus.SUSPENDED);
        agent.setAvailability(AgentAvailability.OFFLINE);
        stubTenantScopedAgent();

        var response = service.updateStatus(agent.getId(),
                new UpdateAgentStatusRequest(AgentAdminStatus.ACTIVE));

        assertThat(response.data().adminStatus()).isEqualTo(AgentAdminStatus.ACTIVE);
        assertThat(response.data().availability()).isEqualTo(AgentAvailability.OFFLINE);
    }

    @Test
    @DisplayName("L9: DISABLED is terminal → 409")
    void disabledIsTerminal() {
        agent.setAdminStatus(AgentAdminStatus.DISABLED);
        stubTenantScopedAgent();

        assertThatThrownBy(() -> service.updateStatus(agent.getId(),
                new UpdateAgentStatusRequest(AgentAdminStatus.ACTIVE)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("L10: same-state status update is idempotent")
    void sameStatusUpdateIsIdempotent() {
        stubTenantScopedAgent();

        var response = service.updateStatus(agent.getId(),
                new UpdateAgentStatusRequest(AgentAdminStatus.ACTIVE));

        assertThat(response.data().adminStatus()).isEqualTo(AgentAdminStatus.ACTIVE);
    }

    // === presence ===

    @Test
    @DisplayName("P1: OFFLINE→AVAILABLE accepted for ACTIVE agent")
    void presenceOnlineAccepted() {
        agent.setAvailability(AgentAvailability.OFFLINE);
        stubTenantScopedAgent();

        var response = service.updatePresence(agent.getId(),
                new UpdateAgentPresenceRequest(AgentAvailability.AVAILABLE));

        assertThat(response.data().availability()).isEqualTo(AgentAvailability.AVAILABLE);
    }

    @Test
    @DisplayName("P2: AVAILABLE→OFFLINE accepted")
    void presenceOfflineAccepted() {
        stubTenantScopedAgent();

        var response = service.updatePresence(agent.getId(),
                new UpdateAgentPresenceRequest(AgentAvailability.OFFLINE));

        assertThat(response.data().availability()).isEqualTo(AgentAvailability.OFFLINE);
    }

    @Test
    @DisplayName("P3: BUSY cannot be declared manually (owned by call lifecycle)")
    void busyCannotBeDeclared() {
        stubTenantScopedAgent();

        assertThatThrownBy(() -> service.updatePresence(agent.getId(),
                new UpdateAgentPresenceRequest(AgentAvailability.BUSY)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("BUSY");
    }

    @Test
    @DisplayName("P4: same-state presence update is idempotent")
    void samePresenceUpdateIsIdempotent() {
        stubTenantScopedAgent();

        var response = service.updatePresence(agent.getId(),
                new UpdateAgentPresenceRequest(AgentAvailability.AVAILABLE));

        assertThat(response.data().availability()).isEqualTo(AgentAvailability.AVAILABLE);
    }

    @ParameterizedTest(name = "P5: presence change for {0} agent rejected")
    @MethodSource("inactiveAgents")
    @DisplayName("P5: suspended/disabled agents cannot change presence")
    void presenceRequiresActiveAgent(AgentAdminStatus status) {
        agent.setAdminStatus(status);
        agent.setAvailability(AgentAvailability.OFFLINE); // real transition, not same-state
        stubTenantScopedAgent();

        assertThatThrownBy(() -> service.updatePresence(agent.getId(),
                new UpdateAgentPresenceRequest(AgentAvailability.AVAILABLE)))
                .isInstanceOf(BusinessException.class);
    }

    static Stream<Arguments> inactiveAgents() {
        return Stream.of(
                Arguments.of(AgentAdminStatus.SUSPENDED),
                Arguments.of(AgentAdminStatus.DISABLED));
    }

    // === availability ===

    @Test
    @DisplayName("A1: available = ACTIVE + AVAILABLE + endpoint + free slot")
    void availableWhenAllConditionsMet() {
        stubTenantScopedAgent();
        stubEnabledEndpoint();
        when(callLegRepository.countByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                agent.getId(), CallLegType.AGENT, AgentSpecifications.ACTIVE_LEG_STATUSES))
                .thenReturn(0);

        var response = service.getAvailability(agent.getId());
        AgentAvailabilityResponse body = response.data();

        assertThat(body.available()).isTrue();
        assertThat(body.reasonCode()).isEqualTo(AgentFoundationReasons.AVAILABLE);
    }

    @Test
    @DisplayName("A2: suspended agent unavailable with AGENT_SUSPENDED")
    void suspendedAgentUnavailable() {
        agent.setAdminStatus(AgentAdminStatus.SUSPENDED);
        stubTenantScopedAgent();

        var body = service.getAvailability(agent.getId()).data();
        assertThat(body.available()).isFalse();
        assertThat(body.reasonCode()).isEqualTo(AgentFoundationReasons.AGENT_SUSPENDED);
        verifyNoInteractions(endpointRepository);
    }

    @Test
    @DisplayName("A3: disabled agent unavailable with AGENT_DISABLED")
    void disabledAgentUnavailable() {
        agent.setAdminStatus(AgentAdminStatus.DISABLED);
        stubTenantScopedAgent();

        var body = service.getAvailability(agent.getId()).data();
        assertThat(body.available()).isFalse();
        assertThat(body.reasonCode()).isEqualTo(AgentFoundationReasons.AGENT_DISABLED);
    }

    @Test
    @DisplayName("A4: offline agent unavailable with AGENT_OFFLINE")
    void offlineAgentUnavailable() {
        agent.setAvailability(AgentAvailability.OFFLINE);
        stubTenantScopedAgent();

        var body = service.getAvailability(agent.getId()).data();
        assertThat(body.available()).isFalse();
        assertThat(body.reasonCode()).isEqualTo(AgentFoundationReasons.AGENT_OFFLINE);
    }

    @Test
    @DisplayName("A5: no enabled endpoint → AGENT_ENDPOINT_INVALID")
    void missingEndpointUnavailable() {
        stubTenantScopedAgent();
        when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                agent.getId(), TENANT_A)).thenReturn(List.of());

        var body = service.getAvailability(agent.getId()).data();
        assertThat(body.available()).isFalse();
        assertThat(body.reasonCode()).isEqualTo(AgentFoundationReasons.AGENT_ENDPOINT_INVALID);
    }

    @Test
    @DisplayName("A6: live agent legs at budget → AGENT_AT_CAPACITY (canonical data)")
    void atCapacityWhenLegsExhausted() {
        stubTenantScopedAgent();
        stubEnabledEndpoint();
        when(callLegRepository.countByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                agent.getId(), CallLegType.AGENT, AgentSpecifications.ACTIVE_LEG_STATUSES))
                .thenReturn(1); // maxConcurrentCalls = 1

        var body = service.getAvailability(agent.getId()).data();
        assertThat(body.available()).isFalse();
        assertThat(body.reasonCode()).isEqualTo(AgentFoundationReasons.AGENT_AT_CAPACITY);
    }

    @Test
    @DisplayName("A7: availability result is deterministic for same state")
    void availabilityIsDeterministic() {
        stubTenantScopedAgent();
        stubEnabledEndpoint();
        when(callLegRepository.countByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                any(), any(), any())).thenReturn(0);

        var first = service.getAvailability(agent.getId()).data();
        var second = service.getAvailability(agent.getId()).data();
        assertThat(first).isEqualTo(second);
    }

    // === endpoints ===

    @Test
    @DisplayName("E1: create SIP endpoint inherits agent tenant ownership")
    void createSipEndpoint() {
        stubTenantScopedAgent();
        when(endpointRepository.save(any(AgentEndpointEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var response = service.createEndpoint(agent.getId(),
                new CreateAgentEndpointRequest(null,
                        com.shivang.obd.voice.call.EndpointType.SIP, "alice@pbx.example"));

        assertThat(response.data().tenantId()).isEqualTo(TENANT_A);
        assertThat(response.data().agentId()).isEqualTo(agent.getId());
        assertThat(response.data().enabled()).isTrue();
    }

    @Test
    @DisplayName("E2: create EXTERNAL_FORWARD endpoint requires E.164 target")
    void createExternalForwardEndpointValidatesE164() {
        stubTenantScopedAgent();

        assertThatThrownBy(() -> service.createEndpoint(agent.getId(),
                new CreateAgentEndpointRequest(null,
                        com.shivang.obd.voice.call.EndpointType.EXTERNAL_FORWARD, "12345")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("E.164");
    }

    @Test
    @DisplayName("E3: WEBRTC endpoint type rejected in VB-4A")
    void webrtcEndpointRejected() {
        stubTenantScopedAgent();

        assertThatThrownBy(() -> service.createEndpoint(agent.getId(),
                new CreateAgentEndpointRequest(null,
                        com.shivang.obd.voice.call.EndpointType.WEBRTC, "webrtc://x")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    @DisplayName("E4: SIP endpoint requires user@host target")
    void sipTargetValidated() {
        stubTenantScopedAgent();

        assertThatThrownBy(() -> service.createEndpoint(agent.getId(),
                new CreateAgentEndpointRequest(null,
                        com.shivang.obd.voice.call.EndpointType.SIP, "not-a-sip")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SIP");
    }

    @Test
    @DisplayName("E5: update endpoint changes dial target")
    void updateEndpointTarget() {
        AgentEndpointEntity endpoint = endpointOf(true);
        stubEndpointVisible(endpoint);

        var response = service.updateEndpoint(endpoint.getId(),
                new UpdateAgentEndpointRequest("bob@pbx.example"));

        assertThat(response.data().dialTarget()).isEqualTo("bob@pbx.example");
    }

    @Test
    @DisplayName("E6: disable endpoint flips enabled=false")
    void disableEndpoint() {
        AgentEndpointEntity endpoint = endpointOf(true);
        stubEndpointVisible(endpoint);

        var response = service.disableEndpoint(endpoint.getId());
        assertThat(response.data().enabled()).isFalse();
    }

    @Test
    @DisplayName("E7: disable already-disabled endpoint is idempotent")
    void disableTwiceIsIdempotent() {
        AgentEndpointEntity endpoint = endpointOf(false);
        stubEndpointVisible(endpoint);

        var response = service.disableEndpoint(endpoint.getId());
        assertThat(response.data().enabled()).isFalse();
    }

    @Test
    @DisplayName("E8: enable endpoint flips enabled=true")
    void enableEndpoint() {
        AgentEndpointEntity endpoint = endpointOf(false);
        stubEndpointVisible(endpoint);

        var response = service.enableEndpoint(endpoint.getId());
        assertThat(response.data().enabled()).isTrue();
    }

    @Test
    @DisplayName("E9: deactivate soft-deletes the endpoint row")
    void deactivateSoftDeletes() {
        AgentEndpointEntity endpoint = endpointOf(true);
        stubEndpointVisible(endpoint);

        var response = service.deactivateEndpoint(endpoint.getId());
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(endpoint.getDeletedAt()).isNotNull();
        verify(endpointRepository).save(endpoint);
    }

    @Test
    @DisplayName("E10: cross-tenant endpoint read fails closed with 404")
    void crossTenantEndpointFailsClosed() {
        UUID foreignTenant = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000009");
        AgentEndpointEntity foreign = new AgentEndpointEntity();
        foreign.setId(UUID.randomUUID());
        foreign.setTenantId(foreignTenant);
        // tenant-scoped query misses the foreign row entirely
        when(endpointRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                foreign.getId(), TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEndpoint(foreign.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === capability enforcement ===

    @Test
    @DisplayName("C1: mutations require AGENT_MANAGE for the owning tenant")
    void mutationsRequireManageCapability() {
        stubTenantScopedAgent();

        service.updateStatus(agent.getId(),
                new UpdateAgentStatusRequest(AgentAdminStatus.SUSPENDED));

        verify(authorizationService).requireCapability(
                USER_ID, "AGENT_MANAGE", AccessCheck.forTenant(TENANT_A));
    }

    @Test
    @DisplayName("C2: reads require AGENT_VIEW for the owning tenant")
    void readsRequireViewCapability() {
        stubTenantScopedAgent();
        lenient().when(callLegRepository.countByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                any(), any(), any())).thenReturn(0);

        service.getAgent(agent.getId());

        verify(authorizationService).requireCapability(
                USER_ID, "AGENT_VIEW", AccessCheck.forTenant(TENANT_A));
    }

    // === helpers ===

    private TenantEntity activeTenant(UUID id) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(id);
        tenant.setName("tenant-" + id);
        tenant.setSlug("t-" + id);
        tenant.setStatus(LifecycleStatus.ACTIVE);
        return tenant;
    }

    private Agent agentOf(UUID tenantId) {
        Agent a = new Agent();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setDisplayName("Foreign Agent");
        a.setAdminStatus(AgentAdminStatus.ACTIVE);
        a.setAvailability(AgentAvailability.OFFLINE);
        a.setMaxConcurrentCalls(1);
        return a;
    }

    private AgentEndpointEntity endpointOf(boolean enabled) {
        AgentEndpointEntity endpoint = new AgentEndpointEntity();
        endpoint.setId(UUID.randomUUID());
        endpoint.setAgentId(agent.getId());
        endpoint.setTenantId(TENANT_A);
        endpoint.setEndpointType(com.shivang.obd.voice.call.EndpointType.SIP);
        endpoint.setDialTarget("alice@pbx.example");
        endpoint.setEnabled(enabled);
        return endpoint;
    }

    private void stubTenantScopedAgent() {
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agent.getId(), TENANT_A))
                .thenReturn(Optional.of(agent));
    }

    private void stubEnabledEndpoint() {
        when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                agent.getId(), TENANT_A)).thenReturn(List.of(endpointOf(true)));
    }

    private void stubEndpointVisible(AgentEndpointEntity endpoint) {
        when(endpointRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                endpoint.getId(), TENANT_A)).thenReturn(Optional.of(endpoint));
        when(callLegRepository.countByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                any(), any(), any())).thenReturn(0);
        lenient().when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                any(), any())).thenReturn(Optional.of(agent));
    }
}
