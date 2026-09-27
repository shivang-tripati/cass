package com.shivang.obd.voice.queue;

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
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.agent.AgentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * VB-4B API slice test. Standalone MockMvc over the real controller and
 * real service (Mockito repositories) — verifies the HTTP contract:
 * validation (400), business-rule (422), conflict (409), not-found (404),
 * envelope shape and JSON binding. Full security wiring (401/403
 * problem-detail responses) is covered by SecuritySliceTest; service-level
 * capability checks by the directory unit tests.
 */
class QueueDirectoryApiSliceTest {

    private static final UUID TENANT_A =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("77777777-0000-4000-8000-000000000001");

    private MockMvc mockMvc;
    private QueueRepository queueRepository;
    private QueueWaitingCallRepository waitingCallRepository;
    private QueueMembershipRepository membershipRepository;
    private AgentRepository agentRepository;
    private Queue queue;

    @BeforeEach
    void setUp() {
        queueRepository = mock(QueueRepository.class);
        membershipRepository = mock(QueueMembershipRepository.class);
        waitingCallRepository = mock(QueueWaitingCallRepository.class);
        agentRepository = mock(AgentRepository.class);
        TenantRepository tenantRepository = mock(TenantRepository.class);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        jakarta.persistence.EntityManager entityManager =
                mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.Query lockQuery = mock(jakarta.persistence.Query.class);
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(lockQuery);
        when(lockQuery.setParameter(any(String.class), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn(Boolean.TRUE);
        when(currentUserProvider.current()).thenReturn(Optional.of(
                new AuthenticatedUser(USER_ID, "user@example.com", null)));
        when(queueRepository.save(any(Queue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(membershipRepository.save(any(QueueMembership.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(membershipRepository.saveAndFlush(any(QueueMembership.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        QueueDirectoryService service = new QueueDirectoryService(
                queueRepository, membershipRepository, waitingCallRepository,
                agentRepository, tenantRepository, authorizationService,
                currentUserProvider, entityManager);
        var controller = new QueueDirectoryController(service);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.shivang.obd.common.exception.GlobalExceptionHandler())
                .build();

        queue = new Queue();
        queue.setId(UUID.fromString("cccccccc-0000-4000-8000-000000000001"));
        queue.setTenantId(TENANT_A);
        queue.setName("Support");
        queue.setStatus(QueueStatus.ACTIVE);
        queue.setMaxWaitingCalls(100);
        queue.setMaxWaitSeconds(300);

        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
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
                {"name":"Billing","maxWaitingCalls":50,"maxWaitSeconds":120}
                """;
        mockMvc.perform(post("/api/v1/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Billing"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.maxWaitingCalls").value(50))
                .andExpect(jsonPath("$.data.maxWaitSeconds").value(120));
    }

    @Test
    @DisplayName("API-2: validation failure → 400 with field errors")
    void validationFailureIs400() throws Exception {
        mockMvc.perform(post("/api/v1/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("API-3: unknown queue → 404 problem detail")
    void unknownQueueIs404() throws Exception {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                UUID.fromString("dddddddd-0000-4000-8000-000000000009"), TENANT_A))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/queues/"
                        + UUID.fromString("dddddddd-0000-4000-8000-000000000009")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("API-4: DISABLED → ACTIVE transition → 409 conflict")
    void disabledTerminalIs409() throws Exception {
        queue.setStatus(QueueStatus.DISABLED);

        mockMvc.perform(put("/api/v1/queues/" + queue.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("API-5: invalid configuration (negative capacity) → 400")
    void invalidCapacityIs400() throws Exception {
        mockMvc.perform(put("/api/v1/queues/" + queue.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxWaitingCalls\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("API-6: overflow enabled without target → 422 business rule")
    void overflowWithoutTargetIs422() throws Exception {
        mockMvc.perform(post("/api/v1/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NoTarget\",\"overflowEnabled\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @DisplayName("API-7: add member → 201 with membership envelope")
    void addMemberReturns201() throws Exception {
        var agentId = UUID.fromString("eeeeeeee-0000-4000-8000-000000000001");
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        var agent = new com.shivang.obd.voice.agent.Agent();
        agent.setId(agentId);
        agent.setTenantId(TENANT_A);
        agent.setDisplayName("Agent");
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agentId, TENANT_A))
                .thenReturn(Optional.of(agent));
        when(membershipRepository.findByQueueIdAndAgentIdAndDeletedAtIsNull(
                queue.getId(), agentId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/queues/" + queue.getId() + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.queueId").value(queue.getId().toString()));
    }

    @Test
    @DisplayName("API-8: update membership status → 200")
    void updateMemberReturns200() throws Exception {
        var agentId = UUID.fromString("eeeeeeee-0000-4000-8000-000000000001");
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        var membership = new QueueMembership();
        membership.setId(UUID.randomUUID());
        membership.setQueueId(queue.getId());
        membership.setAgentId(agentId);
        membership.setTenantId(TENANT_A);
        membership.setStatus(QueueMemberStatus.ACTIVE);
        when(membershipRepository.findByQueueIdAndAgentIdAndDeletedAtIsNull(
                queue.getId(), agentId)).thenReturn(Optional.of(membership));

        mockMvc.perform(put("/api/v1/queues/" + queue.getId() + "/members/" + agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }
}
