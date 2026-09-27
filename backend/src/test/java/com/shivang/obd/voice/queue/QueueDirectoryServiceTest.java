package com.shivang.obd.voice.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.agent.Agent;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentRepository;
import com.shivang.obd.voice.queue.dto.AddQueueMemberRequest;
import com.shivang.obd.voice.queue.dto.CreateQueueRequest;
import com.shivang.obd.voice.queue.dto.QueueCapacityResponse;
import com.shivang.obd.voice.queue.dto.QueueMemberResponse;
import com.shivang.obd.voice.queue.dto.QueueResponse;
import com.shivang.obd.voice.queue.dto.QueueWaitingCallResponse;
import com.shivang.obd.voice.queue.dto.UpdateQueueMemberRequest;
import com.shivang.obd.voice.queue.dto.UpdateQueueRequest;
import com.shivang.obd.voice.queue.dto.UpdateQueueStatusRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-4B unit tests: queue lifecycle, configuration (capacity/timeout/
 * overflow validation), membership management and the waiting-call read
 * model at the service boundary. Tenant isolation and capability
 * enforcement are exercised directly; persistence-level behavior (partial
 * unique index, enums, FKs) is proven by the PostgreSQL suite.
 */
class QueueDirectoryServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID TENANT_B =
            UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");
    private static final UUID USER_ID =
            UUID.fromString("77777777-0000-4000-8000-000000000001");

    private QueueRepository queueRepository;
    private QueueMembershipRepository membershipRepository;
    private QueueWaitingCallRepository waitingCallRepository;
    private AgentRepository agentRepository;
    private TenantRepository tenantRepository;
    private AuthorizationService authorizationService;
    private CurrentUserProvider currentUserProvider;
    private jakarta.persistence.EntityManager entityManager;
    private jakarta.persistence.Query lockQuery;
    private QueueDirectoryService service;

    private Queue queue;

    @BeforeEach
    void setUp() {
        queueRepository = mock(QueueRepository.class);
        membershipRepository = mock(QueueMembershipRepository.class);
        waitingCallRepository = mock(QueueWaitingCallRepository.class);
        agentRepository = mock(AgentRepository.class);
        tenantRepository = mock(TenantRepository.class);
        authorizationService = mock(AuthorizationService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        entityManager = mock(jakarta.persistence.EntityManager.class);
        lockQuery = mock(jakarta.persistence.Query.class);
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(lockQuery);
        when(lockQuery.setParameter(any(String.class), any())).thenReturn(lockQuery);
        // default: advisory lock uncontended
        when(lockQuery.getSingleResult()).thenReturn(Boolean.TRUE);

        service = new QueueDirectoryService(queueRepository, membershipRepository,
                waitingCallRepository, agentRepository, tenantRepository,
                authorizationService, currentUserProvider, entityManager);

        queue = new Queue();
        queue.setId(UUID.fromString("cccccccc-0000-4000-8000-000000000001"));
        queue.setTenantId(TENANT_A);
        queue.setName("Support");
        queue.setStatus(QueueStatus.ACTIVE);
        queue.setMaxWaitingCalls(100);
        queue.setMaxWaitSeconds(300);

        when(currentUserProvider.current())
                .thenReturn(Optional.of(new AuthenticatedUser(
                        USER_ID, "user@example.com", null)));
        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
        when(tenantRepository.findByIdAndDeletedAtIsNull(TENANT_A))
                .thenReturn(Optional.of(activeTenant(TENANT_A)));
        when(queueRepository.save(any(Queue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(membershipRepository.save(any(QueueMembership.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(membershipRepository.saveAndFlush(any(QueueMembership.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    // === Q1..Q6: queue lifecycle + validation ===

    @Test
    @DisplayName("Q1: create queue → ACTIVE defaults, tenant from context")
    void createQueueAppliesDefaults() {
        var response = service.createQueue(new CreateQueueRequest(
                "Billing", null, null, null, null, null));

        assertThat(response.data().status()).isEqualTo(QueueStatus.ACTIVE);
        assertThat(response.data().maxWaitingCalls()).isEqualTo(100);
        assertThat(response.data().maxWaitSeconds()).isEqualTo(300);
        assertThat(response.data().overflowEnabled()).isFalse();
        assertThat(response.data().tenantId()).isEqualTo(TENANT_A);
    }

    @Test
    @DisplayName("Q2: create duplicate-name queue → 409 conflict")
    void createDuplicateNameConflicts() {
        when(queueRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(
                TENANT_A, "Support")).thenReturn(true);

        assertThatThrownBy(() -> service.createQueue(
                new CreateQueueRequest("Support", null, null, null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Q3: create with overflow target → validated and stored")
    void createWithOverflowTarget() {
        Queue overflow = queueWithId(UUID.randomUUID());
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                overflow.getId(), TENANT_A)).thenReturn(Optional.of(overflow));

        var response = service.createQueue(new CreateQueueRequest(
                "Sales", null, null, null, true, overflow.getId()));

        assertThat(response.data().overflowEnabled()).isTrue();
        assertThat(response.data().overflowQueueId()).isEqualTo(overflow.getId());
    }

    @Test
    @DisplayName("Q4: update with self-overflow target → rejected")
    void createWithSelfOverflowRejected() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));

        // Self-overflow can only occur on update: the id is generated at creation.
        assertThatThrownBy(() -> service.updateQueue(queue.getId(), new UpdateQueueRequest(
                null, null, null, null, true, queue.getId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot overflow to itself");
    }

    @Test
    @DisplayName("Q5: overflow enabled without target → rejected")
    void overflowWithoutTargetRejected() {
        assertThatThrownBy(() -> service.createQueue(new CreateQueueRequest(
                "NoTarget", null, null, null, true, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("overflowQueueId is required");
    }

    @Test
    @DisplayName("Q6: overflow target from another tenant → 404 fail closed")
    void crossTenantOverflowTargetRejected() {
        UUID foreignQueueId = UUID.randomUUID();
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(foreignQueueId, TENANT_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createQueue(new CreateQueueRequest(
                "CrossTenant", null, null, null, true, foreignQueueId)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === Q7..Q11: lifecycle transitions ===

    @Test
    @DisplayName("Q7: ACTIVE → INACTIVE → ACTIVE transitions allowed")
    void activeInactiveRoundTrip() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));

        var inactive = service.updateStatus(queue.getId(),
                new UpdateQueueStatusRequest(QueueStatus.INACTIVE));
        assertThat(inactive.data().status()).isEqualTo(QueueStatus.INACTIVE);

        var active = service.updateStatus(queue.getId(),
                new UpdateQueueStatusRequest(QueueStatus.ACTIVE));
        assertThat(active.data().status()).isEqualTo(QueueStatus.ACTIVE);
    }

    @Test
    @DisplayName("Q8: ACTIVE|INACTIVE → DISABLED allowed")
    void disableAllowed() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));

        var response = service.updateStatus(queue.getId(),
                new UpdateQueueStatusRequest(QueueStatus.DISABLED));
        assertThat(response.data().status()).isEqualTo(QueueStatus.DISABLED);
    }

    @Test
    @DisplayName("Q9: DISABLED is terminal → further transition rejected 409")
    void disabledIsTerminal() {
        queue.setStatus(QueueStatus.DISABLED);
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));

        assertThatThrownBy(() -> service.updateStatus(queue.getId(),
                new UpdateQueueStatusRequest(QueueStatus.ACTIVE)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    @DisplayName("Q10: same-state status update is idempotent")
    void sameStatusIdempotent() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));

        var response = service.updateStatus(queue.getId(),
                new UpdateQueueStatusRequest(QueueStatus.ACTIVE));
        assertThat(response.data().status()).isEqualTo(QueueStatus.ACTIVE);
    }

    @Test
    @DisplayName("Q11: get foreign-tenant queue → 404 fail closed")
    void getForeignQueueNotFound() {
        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_B, null);
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_B))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getQueue(queue.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === Q12..Q15: configuration updates ===

    @Test
    @DisplayName("Q12: update capacity/timeout applies validated values")
    void updateConfiguration() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));

        var response = service.updateQueue(queue.getId(), new UpdateQueueRequest(
                null, "Frontline", 50, 120, null, null));
        assertThat(response.data().maxWaitingCalls()).isEqualTo(50);
        assertThat(response.data().maxWaitSeconds()).isEqualTo(120);
        assertThat(response.data().description()).isEqualTo("Frontline");
    }

    @Test
    @DisplayName("Q13: renaming into an existing name → 409")
    void renameConflict() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        when(queueRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(
                TENANT_A, "Other")).thenReturn(true);

        assertThatThrownBy(() -> service.updateQueue(queue.getId(),
                new UpdateQueueRequest("Other", null, null, null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Q14: disabling overflow clears the stored target")
    void disableOverflowClearsTarget() {
        queue.setOverflowEnabled(true);
        queue.setOverflowQueueId(UUID.randomUUID());
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));

        var response = service.updateQueue(queue.getId(), new UpdateQueueRequest(
                null, null, null, null, false, null));
        assertThat(response.data().overflowEnabled()).isFalse();
        assertThat(response.data().overflowQueueId()).isNull();
    }

    @Test
    @DisplayName("Q15: update to DISABLED overflow target → rejected")
    void updateToDisabledOverflowTargetRejected() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        UUID targetId = UUID.randomUUID();
        Queue disabledTarget = queueWithId(targetId);
        disabledTarget.setStatus(QueueStatus.DISABLED);
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(targetId, TENANT_A))
                .thenReturn(Optional.of(disabledTarget));

        assertThatThrownBy(() -> service.updateQueue(queue.getId(),
                new UpdateQueueRequest(null, null, null, null, true, targetId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DISABLED");
    }

    // === M1..M7: membership ===

    @Test
    @DisplayName("M1: add member → ACTIVE membership, agent same tenant")
    void addMemberCreatesMembership() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        Agent agent = agentWithId(UUID.randomUUID());
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agent.getId(), TENANT_A))
                .thenReturn(Optional.of(agent));
        when(membershipRepository.findByQueueIdAndAgentIdAndDeletedAtIsNull(
                queue.getId(), agent.getId())).thenReturn(Optional.empty());

        var response = service.addMember(queue.getId(),
                new AddQueueMemberRequest(agent.getId()));

        assertThat(response.data().status()).isEqualTo(QueueMemberStatus.ACTIVE);
        assertThat(response.data().queueId()).isEqualTo(queue.getId());
        assertThat(response.data().agentId()).isEqualTo(agent.getId());
    }

    @Test
    @DisplayName("M2: add existing ACTIVE member → idempotent, returns existing row")
    void addExistingActiveMemberIdempotent() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        Agent agent = agentWithId(UUID.randomUUID());
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agent.getId(), TENANT_A))
                .thenReturn(Optional.of(agent));
        QueueMembership existing = membership(queue.getId(), agent.getId());
        when(membershipRepository.findByQueueIdAndAgentIdAndDeletedAtIsNull(
                queue.getId(), agent.getId())).thenReturn(Optional.of(existing));

        var response = service.addMember(queue.getId(),
                new AddQueueMemberRequest(agent.getId()));

        assertThat(response.data().id()).isEqualTo(existing.getId());
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("M3: add existing INACTIVE member → reactivated to ACTIVE")
    void addInactiveMemberReactivates() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        Agent agent = agentWithId(UUID.randomUUID());
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agent.getId(), TENANT_A))
                .thenReturn(Optional.of(agent));
        QueueMembership existing = membership(queue.getId(), agent.getId());
        existing.setStatus(QueueMemberStatus.INACTIVE);
        when(membershipRepository.findByQueueIdAndAgentIdAndDeletedAtIsNull(
                queue.getId(), agent.getId())).thenReturn(Optional.of(existing));

        var response = service.addMember(queue.getId(),
                new AddQueueMemberRequest(agent.getId()));

        assertThat(response.data().status()).isEqualTo(QueueMemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("M4: concurrent duplicate add (loser re-reads winner after blocking lock) → collapses to the winner's row")
    void concurrentDuplicateAddCollapses() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        Agent agent = agentWithId(UUID.randomUUID());
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agent.getId(), TENANT_A))
                .thenReturn(Optional.of(agent));
        QueueMembership winner = membership(queue.getId(), agent.getId());
        when(membershipRepository.findByQueueIdAndAgentIdAndDeletedAtIsNull(
                queue.getId(), agent.getId()))
                .thenReturn(Optional.empty())          // first lookup: nothing there
                .thenReturn(Optional.of(winner));      // post-contention re-read
        when(lockQuery.getSingleResult()).thenReturn(Boolean.FALSE); // lock contended

        var response = service.addMember(queue.getId(),
                new AddQueueMemberRequest(agent.getId()));

        assertThat(response.data().id()).isEqualTo(winner.getId());
        verify(membershipRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("M4b: concurrent duplicate add (lock won) → single insert, no duplicate")
    void concurrentDuplicateAddWinnerInsertsOnce() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        Agent agent = agentWithId(UUID.randomUUID());
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agent.getId(), TENANT_A))
                .thenReturn(Optional.of(agent));
        when(membershipRepository.findByQueueIdAndAgentIdAndDeletedAtIsNull(
                queue.getId(), agent.getId())).thenReturn(Optional.empty());
        when(membershipRepository.saveAndFlush(any(QueueMembership.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var response = service.addMember(queue.getId(),
                new AddQueueMemberRequest(agent.getId()));

        assertThat(response.data().agentId()).isEqualTo(agent.getId());
        verify(membershipRepository).saveAndFlush(any(QueueMembership.class));
    }

    @Test
    @DisplayName("M5: add cross-tenant agent → 404 fail closed")
    void addCrossTenantAgentNotFound() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        UUID foreignAgentId = UUID.randomUUID();
        when(agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(foreignAgentId, TENANT_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(queue.getId(),
                new AddQueueMemberRequest(foreignAgentId)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("M6: membership INACTIVE excludes agent without touching the agent")
    void membershipInactiveDoesNotTouchAgent() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        Agent agent = agentWithId(UUID.randomUUID());
        QueueMembership membership = membership(queue.getId(), agent.getId());
        when(membershipRepository.findByQueueIdAndAgentIdAndDeletedAtIsNull(
                queue.getId(), agent.getId())).thenReturn(Optional.of(membership));

        var response = service.updateMember(queue.getId(), agent.getId(),
                new UpdateQueueMemberRequest(QueueMemberStatus.INACTIVE));

        assertThat(response.data().status()).isEqualTo(QueueMemberStatus.INACTIVE);
        verify(agentRepository, never()).save(any());
    }

    @Test
    @DisplayName("M7: remove member → soft delete only; agent untouched")
    void removeMemberSoftDeletes() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        Agent agent = agentWithId(UUID.randomUUID());
        QueueMembership membership = membership(queue.getId(), agent.getId());
        when(membershipRepository.findByQueueIdAndAgentIdAndDeletedAtIsNull(
                queue.getId(), agent.getId())).thenReturn(Optional.of(membership));

        service.removeMember(queue.getId(), agent.getId());

        assertThat(membership.getDeletedAt()).isNotNull();
        verify(membershipRepository).save(membership);
        verify(agentRepository, never()).save(any());
    }

    // === W1..W4: waiting calls + capacity ===

    @Test
    @DisplayName("W1: capacity read model → configured vs live WAITING count")
    void capacityReadModel() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        when(waitingCallRepository.countByQueueIdAndTenantIdAndStatusAndDeletedAtIsNull(
                queue.getId(), TENANT_A, QueueWaitingCallStatus.WAITING)).thenReturn(17L);

        ApiResponse<QueueCapacityResponse> response = service.getCapacity(queue.getId());

        assertThat(response.data().configuredCapacity()).isEqualTo(100);
        assertThat(response.data().currentWaiting()).isEqualTo(17);
        assertThat(response.data().remainingCapacity()).isEqualTo(83);
    }

    @Test
    @DisplayName("W2: capacity never reports negative remaining")
    void capacityNeverNegative() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        when(waitingCallRepository.countByQueueIdAndTenantIdAndStatusAndDeletedAtIsNull(
                queue.getId(), TENANT_A, QueueWaitingCallStatus.WAITING)).thenReturn(150L);

        ApiResponse<QueueCapacityResponse> response = service.getCapacity(queue.getId());

        assertThat(response.data().remainingCapacity()).isZero();
    }

    @Test
    @DisplayName("W3: waiting calls listed in deterministic (enteredAt, id) order")
    void waitingCallsListedDeterministically() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        QueueWaitingCall first = waitingCall(UUID.randomUUID(), queue.getId());
        QueueWaitingCall second = waitingCall(UUID.randomUUID(), queue.getId());
        when(waitingCallRepository
                .findByQueueIdAndTenantIdAndStatusAndDeletedAtIsNullOrderByEnteredAtAscIdAsc(
                        queue.getId(), TENANT_A, QueueWaitingCallStatus.WAITING))
                .thenReturn(List.of(first, second));

        ApiResponse<List<QueueWaitingCallResponse>> response =
                service.listWaitingCalls(queue.getId());

        assertThat(response.data()).hasSize(2);
        assertThat(response.data().get(0).id()).isEqualTo(first.getId());
        assertThat(response.data().get(0).status()).isEqualTo(QueueWaitingCallStatus.WAITING);
    }

    @Test
    @DisplayName("W4: waiting-call response duplicates no call attributes")
    void waitingCallExposesSessionReferenceOnly() {
        when(queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queue.getId(), TENANT_A))
                .thenReturn(Optional.of(queue));
        QueueWaitingCall wc = waitingCall(UUID.randomUUID(), queue.getId());
        when(waitingCallRepository
                .findByQueueIdAndTenantIdAndStatusAndDeletedAtIsNullOrderByEnteredAtAscIdAsc(
                        queue.getId(), TENANT_A, QueueWaitingCallStatus.WAITING))
                .thenReturn(List.of(wc));

        ApiResponse<List<QueueWaitingCallResponse>> response =
                service.listWaitingCalls(queue.getId());

        QueueWaitingCallResponse dto = response.data().get(0);
        assertThat(dto.callSessionId()).isEqualTo(wc.getCallSessionId());
        // the DTO carries only queue + canonical session reference + timing
        assertThat(dto.getClass().getRecordComponents()).hasSize(6);
    }

    // === helpers ===

    private TenantEntity activeTenant(UUID tenantId) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(tenantId);
        tenant.setStatus(LifecycleStatus.ACTIVE);
        return tenant;
    }

    private Queue queueWithId(UUID id) {
        Queue q = new Queue();
        q.setId(id);
        q.setTenantId(TENANT_A);
        q.setName("Overflow-" + id);
        q.setStatus(QueueStatus.ACTIVE);
        return q;
    }

    private Agent agentWithId(UUID id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setTenantId(TENANT_A);
        agent.setDisplayName("Agent");
        agent.setAdminStatus(AgentAdminStatus.ACTIVE);
        return agent;
    }

    private QueueMembership membership(UUID queueId, UUID agentId) {
        QueueMembership m = new QueueMembership();
        m.setId(UUID.randomUUID());
        m.setQueueId(queueId);
        m.setAgentId(agentId);
        m.setTenantId(TENANT_A);
        m.setStatus(QueueMemberStatus.ACTIVE);
        m.setCreatedAt(Instant.now());
        return m;
    }

    private QueueWaitingCall waitingCall(UUID sessionId, UUID queueId) {
        QueueWaitingCall wc = new QueueWaitingCall();
        wc.setId(UUID.randomUUID());
        wc.setTenantId(TENANT_A);
        wc.setQueueId(queueId);
        wc.setCallSessionId(sessionId);
        wc.setStatus(QueueWaitingCallStatus.WAITING);
        wc.setEnteredAt(Instant.now());
        return wc;
    }
}
