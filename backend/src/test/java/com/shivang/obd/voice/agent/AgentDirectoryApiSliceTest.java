package com.shivang.obd.voice.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.agent.dto.AgentResponse;
import com.shivang.obd.voice.agent.dto.CreateAgentRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentPresenceRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentStatusRequest;
import com.shivang.obd.voice.call.CallLegRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * VB-4A API slice test. Standalone MockMvc over the real controller and
 * real services (Mockito repositories) — verifies the HTTP contract the
 * service unit tests cannot: validation (400), business-rule (422),
 * not-found (404), envelope shape and JSON binding. Full security wiring
 * (401/403 problem-detail responses) is covered by SecuritySliceTest;
 * service-level capability checks by the directory unit tests.
 */
class AgentDirectoryApiSliceTest {

    private static final UUID TENANT_A =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("77777777-0000-4000-8000-000000000001");

    private MockMvc mockMvc;
    private AgentRepository agentRepository;
    private AgentEndpointRepository endpointRepository;

    private Agent agent;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        endpointRepository = mock(AgentEndpointRepository.class);
        AgentReservationRepository reservationRepository = mock(AgentReservationRepository.class);
        CallLegRepository callLegRepository = mock(CallLegRepository.class);
        TenantRepository tenantRepository = mock(TenantRepository.class);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.current()).thenReturn(Optional.of(
                new AuthenticatedUser(USER_ID, "user@example.com", null)));
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentDirectoryService directoryService = new AgentDirectoryService(
                agentRepository, endpointRepository, reservationRepository,
                callLegRepository, tenantRepository, authorizationService, currentUserProvider);
        AgentCallQueryService callQueryService = new AgentCallQueryService(
                agentRepository, Mockito.mock(CallLegRepository.class),
                Mockito.mock(com.shivang.obd.voice.call.CallSessionRepository.class),
                tenantRepository, authorizationService, currentUserProvider);
        AgentDirectoryController controller =
                new AgentDirectoryController(directoryService, callQueryService,
                        Mockito.mock(com.shivang.obd.voice.outbound.AgentOutboundApiService.class));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.shivang.obd.common.exception.GlobalExceptionHandler())
                .build();

        agent = new Agent();
        agent.setId(UUID.fromString("eeeeeeee-0000-4000-8000-000000000001"));
        agent.setTenantId(TENANT_A);
        agent.setDisplayName("Agent One");
        agent.setAdminStatus(AgentAdminStatus.ACTIVE);
        agent.setAvailability(AgentAvailability.AVAILABLE);
        agent.setMaxConcurrentCalls(1);

        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agent.getId(), TENANT_A))
                .thenReturn(Optional.of(agent));
        TenantEntity activeTenant = new TenantEntity();
        activeTenant.setId(TENANT_A);
        activeTenant.setName("tenant-a");
        activeTenant.setStatus(com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE);
        when(tenantRepository.findByIdAndDeletedAtIsNull(TENANT_A))
                .thenReturn(Optional.of(activeTenant));
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    @Test
    @DisplayName("API-1: create returns 201 with the ApiResponse envelope")
    void createReturns201WithEnvelope() throws Exception {
        String body = """
                {"displayName":"Api Agent","maxConcurrentCalls":2}
                """;
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.displayName").value("Api Agent"))
                .andExpect(jsonPath("$.data.adminStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.availability").value("OFFLINE"));
    }

    @Test
    @DisplayName("API-2: validation failure → 400 with field errors")
    void validationFailureIs400() throws Exception {
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("API-3: unknown agent → 404 problem detail")
    void unknownAgentIs404() throws Exception {
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                UUID.fromString("dddddddd-0000-4000-8000-000000000009"), TENANT_A))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/agents/"
                        + UUID.fromString("dddddddd-0000-4000-8000-000000000009")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("API-4: BUSY presence declaration → 422 business rule")
    void busyPresenceIs422() throws Exception {
        mockMvc.perform(put("/api/v1/agents/" + agent.getId() + "/presence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availability\":\"BUSY\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @DisplayName("API-5: get agent returns envelope with derived activeCallCount")
    void getAgentEnvelope() throws Exception {
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agent.getId(), TENANT_A))
                .thenReturn(Optional.of(agent));

        mockMvc.perform(get("/api/v1/agents/" + agent.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(agent.getId().toString()))
                .andExpect(jsonPath("$.data.activeCallCount").value(0))
                .andExpect(jsonPath("$.data.maxConcurrentCalls").value(1));
    }

    @Test
    @DisplayName("API-6: JSON body binds to DTO records (round-trip)")
    void jsonBindingRoundTrip() throws Exception {
        String body = """
                {"displayName":"Bind Agent","maxConcurrentCalls":4,"userId":null}
                """;
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // service received a properly bound record
        org.mockito.Mockito.verify(agentRepository).save(org.mockito.ArgumentMatchers.argThat(
                a -> "Bind Agent".equals(a.getDisplayName())
                        && a.getMaxConcurrentCalls() == 4));
    }

    @Test
    @DisplayName("API-7: DISABLED terminal transition → 409")
    void disabledTerminalIs409() throws Exception {
        agent.setAdminStatus(AgentAdminStatus.DISABLED);

        mockMvc.perform(put("/api/v1/agents/" + agent.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminStatus\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("API-8: presence update accepted via API")
    void presenceViaApi() throws Exception {
        agent.setAvailability(AgentAvailability.OFFLINE);

        mockMvc.perform(put("/api/v1/agents/" + agent.getId() + "/presence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availability\":\"AVAILABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability").value("AVAILABLE"));
    }
}
