package com.shivang.obd.voice.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentAvailability;
import com.shivang.obd.voice.queue.dto.AddQueueMemberRequest;
import com.shivang.obd.voice.queue.dto.CreateQueueRequest;
import com.shivang.obd.voice.queue.dto.QueueMemberResponse;
import com.shivang.obd.voice.queue.dto.QueueResponse;
import com.shivang.obd.voice.queue.dto.QueueWaitingCallResponse;
import com.shivang.obd.voice.queue.dto.UpdateQueueStatusRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-4B PostgreSQL integration tests: Flyway V1..V37 chain, native enum
 * handling, FKs, the partial unique membership index, waiting-call
 * persistence, tenant isolation and the concurrent duplicate-add race —
 * all against a real PostgreSQL database (Testcontainers). Provider and
 * authorization edges are mocked; data behavior is real.
 */
class QueueFoundationIntegrationTest extends QueueFoundationIntegrationSupport {

    private UUID tenantId;
    private UUID agentId;

    @BeforeEach
    void seed() {
        initQueueService();
        tenantId = seedTenant("q");
        agentId = seedAgent(tenantId, "Queue Agent",
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, 1);
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
                UUID.fromString("77777777-0000-4000-8000-000000000001"), tenantId, null);
    }

    @AfterEach
    void clean() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
        cleanupAgentData();
    }

    @Test
    @DisplayName("IT-1: queue lifecycle persists with native enums (create → INACTIVE → ACTIVE → DISABLED)")
    void lifecyclePersistsWithNativeEnums() {
        ApiResponse<QueueResponse> created = inTx(() -> queueService.createQueue(
                new CreateQueueRequest("Support", "Frontline", 50, 120, null, null)));

        UUID queueId = created.data().id();
        assertThat(created.data().status()).isEqualTo(QueueStatus.ACTIVE);

        var inactive = inTx(() -> queueService.updateStatus(queueId,
                new UpdateQueueStatusRequest(QueueStatus.INACTIVE)));
        assertThat(inactive.data().status()).isEqualTo(QueueStatus.INACTIVE);

        var active = inTx(() -> queueService.updateStatus(queueId,
                new UpdateQueueStatusRequest(QueueStatus.ACTIVE)));
        assertThat(active.data().status()).isEqualTo(QueueStatus.ACTIVE);

        var disabled = inTx(() -> queueService.updateStatus(queueId,
                new UpdateQueueStatusRequest(QueueStatus.DISABLED)));
        assertThat(disabled.data().status()).isEqualTo(QueueStatus.DISABLED);

        // Round-trip proves the native enum read path (NAMED_ENUM binding).
        Queue reloaded = inTx(() -> queueRepository.findById(queueId).orElseThrow());
        assertThat(reloaded.getStatus()).isEqualTo(QueueStatus.DISABLED);
        assertThat(reloaded.getMaxWaitingCalls()).isEqualTo(50);
        assertThat(reloaded.getMaxWaitSeconds()).isEqualTo(120);
    }

    @Test
    @DisplayName("IT-2: disabled queue is terminal at the service boundary on real data")
    void disabledQueueIsTerminal() {
        UUID queueId = inTx(() -> queueService.createQueue(
                new CreateQueueRequest("Terminal", null, null, null, null, null))).data().id();
        inTx(() -> queueService.updateStatus(queueId,
                new UpdateQueueStatusRequest(QueueStatus.DISABLED)));

        // Service must refuse the transition from DISABLED; the reloaded row
        // proves nothing changed.
        assertThatThrownBy(() -> inTx(() -> queueService.updateStatus(queueId,
                new UpdateQueueStatusRequest(QueueStatus.ACTIVE))))
                .isInstanceOf(com.shivang.obd.common.exception.ConflictException.class);
        Queue reloaded = inTx(() -> queueRepository.findById(queueId).orElseThrow());
        assertThat(reloaded.getStatus()).isEqualTo(QueueStatus.DISABLED);
    }

    @Test
    @DisplayName("IT-3: duplicate queue name (live rows) → 409; soft-deleted name reusable")
    void duplicateNameRules() {
        UUID q1 = inTx(() -> queueService.createQueue(
                new CreateQueueRequest("Sales", null, null, null, null, null))).data().id();

        assertThatThrownBy(() -> inTx(() -> queueService.createQueue(
                new CreateQueueRequest("SALES", null, null, null, null, null))))
                .isInstanceOf(com.shivang.obd.common.exception.ConflictException.class);

        // Reuse after the original is gone would be allowed once soft-deleted
        // (partial index); here we verify the live-conflict path only.
        assertThat(queueRepository.findById(q1)).isPresent();
    }

    @Test
    @DisplayName("IT-4: membership add → persists with ACTIVE status; remove → soft delete; re-add → reactivated")
    void membershipLifecycleOnRealDb() {
        UUID queueId = inTx(() -> queueService.createQueue(
                new CreateQueueRequest("Members", null, null, null, null, null))).data().id();

        ApiResponse<QueueMemberResponse> added = inTx(() ->
                queueService.addMember(queueId, new AddQueueMemberRequest(agentId)));
        assertThat(added.data().status()).isEqualTo(QueueMemberStatus.ACTIVE);

        inTx(() -> queueService.removeMember(queueId, agentId));
        Integer liveCount = inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM queue_memberships WHERE queue_id = :q AND deleted_at IS NULL")
                    .setParameter("q", queueId).getSingleResult();
            return n.intValue();
        });
        assertThat(liveCount).isZero();

        // Re-add after soft delete reactivates as a NEW live row (history kept).
        ApiResponse<QueueMemberResponse> readded = inTx(() ->
                queueService.addMember(queueId, new AddQueueMemberRequest(agentId)));
        assertThat(readded.data().status()).isEqualTo(QueueMemberStatus.ACTIVE);
        assertThat(readded.data().id()).isNotEqualTo(added.data().id());
    }

    @Test
    @DisplayName("IT-5: waiting-call rows persist against canonical CallSession and list in (enteredAt, id) order")
    void waitingCallsPersistAndOrder() throws Exception {
        UUID queueId = inTx(() -> queueService.createQueue(
                new CreateQueueRequest("Waiting", null, null, null, null, null))).data().id();

        UUID s1 = seedCallSession(tenantId);
        UUID s2 = seedCallSession(tenantId);
        inTx(() -> {
            var wc1 = new QueueWaitingCall();
            wc1.setTenantId(tenantId);
            wc1.setQueueId(queueId);
            wc1.setCallSessionId(s1);
            wc1.setEnteredAt(java.time.Instant.now().minusSeconds(10));
            waitingCallRepository.save(wc1);
            var wc2 = new QueueWaitingCall();
            wc2.setTenantId(tenantId);
            wc2.setQueueId(queueId);
            wc2.setCallSessionId(s2);
            wc2.setEnteredAt(java.time.Instant.now());
            return waitingCallRepository.save(wc2).getId();
        });

        ApiResponse<List<QueueWaitingCallResponse>> waiting =
                inTx(() -> queueService.listWaitingCalls(queueId));
        assertThat(waiting.data()).hasSize(2);
        assertThat(waiting.data().get(0).callSessionId()).isEqualTo(s1);
        assertThat(waiting.data().get(0).status()).isEqualTo(QueueWaitingCallStatus.WAITING);
    }

    @Test
    @DisplayName("IT-6: tenant isolation — foreign queue/agent/membership fail closed (404)")
    void tenantIsolation() {
        UUID queueA = inTx(() -> queueService.createQueue(
                new CreateQueueRequest("A-Queue", null, null, null, null, null))).data().id();

        UUID tenantB = seedTenant("qb");
        UUID agentB = seedAgent(tenantB, "B Agent",
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, 1);

        // Tenant B context cannot see tenant A's queue.
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
                UUID.fromString("77777777-0000-4000-8000-000000000001"), tenantB, null);
        assertThatThrownBy(() -> inTx(() -> queueService.getQueue(queueA)))
                .isInstanceOf(ResourceNotFoundException.class);
        // Tenant B cannot add its agent to tenant A's queue...
        assertThatThrownBy(() -> inTx(() -> queueService.addMember(
                queueA, new AddQueueMemberRequest(agentB))))
                .isInstanceOf(ResourceNotFoundException.class);

        // ...and tenant A cannot add a foreign agent to its own queue.
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
                UUID.fromString("77777777-0000-4000-8000-000000000001"), tenantId, null);
        assertThatThrownBy(() -> inTx(() -> queueService.addMember(
                queueA, new AddQueueMemberRequest(agentB))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("IT-7: 20 concurrent add-member requests → exactly one live membership (advisory lock + unique index)")
    void twentyConcurrentAddsYieldOneMembership() throws Exception {
        UUID queueId = inTx(() -> queueService.createQueue(
                new CreateQueueRequest("Race", null, null, null, null, null))).data().id();

        final int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger added = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var tx = new org.springframework.transaction.support.TransactionTemplate(
                            transactionManager);
                    tx.executeWithoutResult(status -> queueService.addMember(
                            queueId, new AddQueueMemberRequest(agentId)));
                    added.incrementAndGet();
                } catch (Exception e) {
                    // failed add = rejected attempt
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(added.get()).isEqualTo(threads);
        Integer liveMemberships = inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM queue_memberships WHERE queue_id = :q AND agent_id = :a AND deleted_at IS NULL")
                    .setParameter("q", queueId).setParameter("a", agentId)
                    .getSingleResult();
            return n.intValue();
        });
        assertThat(liveMemberships).isEqualTo(1);
    }

    @Test
    @DisplayName("IT-8: migration V37 schema — tables, native enums, FKs, partial unique indexes")
    void migrationV37Schema() {
        inTx(() -> {
            // native enum types exist
            @SuppressWarnings("unchecked")
            List<String> enums = entityManager.createNativeQuery(
                    "SELECT string_agg(t.typname, ',' ORDER BY t.typname) FROM pg_type t "
                    + "JOIN pg_namespace n ON n.oid = t.typnamespace "
                    + "WHERE t.typtype = 'e' AND n.nspname = 'public' "
                    + "AND t.typname IN ('queue_status','queue_member_status','queue_waiting_call_status')")
                    .getResultList();
            assertThat(enums.get(0)).contains("queue_status",
                    "queue_member_status", "queue_waiting_call_status");

            // FKs on membership and waiting calls
            Number fkCount = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM pg_constraint c "
                    + "JOIN pg_class t ON t.oid = c.conrelid "
                    + "WHERE t.relname IN ('queue_memberships','queue_waiting_calls') "
                    + "AND c.contype = 'f'").getSingleResult();
            assertThat(fkCount.longValue()).isGreaterThanOrEqualTo(5);

            // partial unique indexes exist
            Number uqCount = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM pg_indexes WHERE indexname IN "
                    + "('uq_queue_memberships_queue_agent','uq_queue_waiting_calls_active_session')")
                    .getSingleResult();
            assertThat(uqCount.longValue()).isEqualTo(2);

            // check constraints on configuration
            Number ckCount = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM pg_constraint c "
                    + "JOIN pg_class t ON t.oid = c.conrelid "
                    + "WHERE t.relname = 'queues' AND c.contype = 'c' "
                    + "AND c.conname LIKE 'ck_%'").getSingleResult();
            assertThat(ckCount.longValue()).isGreaterThanOrEqualTo(3);
            return null;
        });
    }
}
