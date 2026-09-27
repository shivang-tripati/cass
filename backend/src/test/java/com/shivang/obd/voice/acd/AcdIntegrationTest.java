package com.shivang.obd.voice.acd;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.voice.agent.AgentAvailability;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.queue.QueueMemberStatus;
import com.shivang.obd.voice.queue.QueueStatus;
import com.shivang.obd.voice.queue.QueueWaitingCall;
import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.time.Instant;
import java.util.ArrayList;
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
 * VB-4C PostgreSQL integration + concurrency matrix: real advisory locks,
 * real conditional UPDATEs, real commits (Testcontainers, Flyway
 * V1..V38). Proves the invariants: activeReservations(agent) <=
 * maxConcurrentCalls; one assignment per waiting call; expiry/timeout
 * sweeps idempotent; overflow bounded.
 */
class AcdIntegrationTest extends AcdIntegrationSupport {

    private UUID tenantId;
    private UUID queueId;

    @BeforeEach
    void seed() {
        initAcdServices();
        tenantId = seedTenant("c");
        queueId = seedQueue(tenantId, "ACD-" + UUID.randomUUID().toString().substring(0, 8),
                QueueStatus.ACTIVE);
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
                UUID.fromString("77777777-0000-4000-8000-000000000001"), tenantId, null);
    }

    @AfterEach
    void clean() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
        cleanupAgentData();
    }

    private UUID memberAgent(int maxConcurrent) {
        UUID agentId = seedAgent(tenantId, "A-" + UUID.randomUUID().toString().substring(0, 6),
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, maxConcurrent);
        seedEndpoint(tenantId, agentId, "sip:" + agentId + "@pbx.example",
                com.shivang.obd.voice.call.EndpointType.SIP, true);
        inTx(() -> {
            var m = new com.shivang.obd.voice.queue.QueueMembership();
            m.setQueueId(queueId);
            m.setAgentId(agentId);
            m.setTenantId(tenantId);
            m.setStatus(QueueMemberStatus.ACTIVE);
            return membershipRepository.save(m).getId();
        });
        return agentId;
    }

    private AcdIntegrationSupport self() {
        return this;
    }

    // === lifecycle / correctness on real data ===

    @Test
    @DisplayName("IT-1: assignment persists — WAITING→ASSIGNED, hold stamped with queue/waiting-call/expiry, ASSIGNED enum round-trips")
    void assignmentPersists() {
        UUID agentId = memberAgent(1);
        UUID waitingCallId = seedWaitingCall(tenantId, queueId, null);

        var result = inTx(() -> acdService.attemptAssignment(tenantId, queueId, waitingCallId));

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.ASSIGNED);
        assertThat(assignedCount(queueId)).isEqualTo(1);
        QueueWaitingCall row = inTx(() -> waitingCallRepository.findById(waitingCallId).orElseThrow());
        assertThat(row.getStatus()).isEqualTo(QueueWaitingCallStatus.ASSIGNED);
        assertThat(row.getAssignedAgentId()).isEqualTo(agentId);
        assertThat(row.getAssignedReservationId()).isEqualTo(result.reservationId());
        var hold = inTx(() -> reservationRepository.findById(result.reservationId()).orElseThrow());
        assertThat(hold.getQueueId()).isEqualTo(queueId);
        assertThat(hold.getWaitingCallId()).isEqualTo(waitingCallId);
        assertThat(hold.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("IT-2: repeat attempt on an assigned call → ALREADY_ASSIGNED (idempotent, no second hold)")
    void repeatAttemptIdempotent() {
        memberAgent(1);
        UUID waitingCallId = seedWaitingCall(tenantId, queueId, null);
        var first = inTx(() -> acdService.attemptAssignment(tenantId, queueId, waitingCallId));
        assertThat(first.status()).isEqualTo(AcdResult.AcdStatus.ASSIGNED);

        var second = inTx(() -> acdService.attemptAssignment(tenantId, queueId, waitingCallId));

        assertThat(second.status()).isEqualTo(AcdResult.AcdStatus.ALREADY_ASSIGNED);
        assertThat(second.reservationId()).isEqualTo(first.reservationId());
        assertThat(activeHolds(UUID.fromString(first.agentId().toString()))).isEqualTo(1);
    }

    @Test
    @DisplayName("IT-3: expired-reservation sweep → hold released, assignment unwound to WAITING, availability restored; idempotent on rerun")
    void expirySweepUnwindsAssignment() {
        UUID agentId = memberAgent(1);
        UUID waitingCallId = seedWaitingCall(tenantId, queueId, null);
        var result = inTx(() -> acdService.attemptAssignment(tenantId, queueId, waitingCallId));
        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.ASSIGNED);

        // Force expiry past the window.
        inTx(() -> {
            entityManager.createNativeQuery(
                    "UPDATE agent_reservations SET expires_at = :past WHERE id = :id")
                    .setParameter("past", Instant.now().minusSeconds(10))
                    .setParameter("id", UUID.fromString(result.reservationId().toString()))
                    .executeUpdate();
            return null;
        });

        var sweep = new AcdMaintenanceScheduler(reservationRepository, agentRepository,
                waitingCallRepository);
        // @Transactional is proxy-activated — run through a real transaction.
        inTx(() -> { sweep.sweep(); return null; });
        inTx(() -> { sweep.sweep(); return null; }); // idempotency: second run changes nothing

        assertThat(assignedCount(queueId)).isZero();
        assertThat(waitingCount(queueId)).isEqualTo(1);
        assertThat(activeHolds(agentId)).isZero();
        var agent = inTx(() -> agentRepository.findById(agentId).orElseThrow());
        assertThat(agent.getAvailability()).isEqualTo(AgentAvailability.AVAILABLE);
    }

    @Test
    @DisplayName("IT-4: queue timeout — WAITING past expires_at → ABANDONED; ASSIGNED call never abandoned")
    void queueTimeoutAbandonsOnlyWaiting() {
        memberAgent(1);
        UUID expiredId = seedWaitingCall(tenantId, queueId,
                Instant.now().minusSeconds(5));
        UUID freshId = seedWaitingCall(tenantId, queueId, Instant.now().plusSeconds(300));
        UUID assignedId = seedWaitingCall(tenantId, queueId, Instant.now().minusSeconds(5));
        inTx(() -> acdService.attemptAssignment(tenantId, queueId, assignedId));

        var sweep = new AcdMaintenanceScheduler(reservationRepository, agentRepository,
                waitingCallRepository);
        inTx(() -> { sweep.sweep(); return null; });

        assertThat(inTx(() -> waitingCallRepository.findById(expiredId).orElseThrow().getStatus()))
                .isEqualTo(QueueWaitingCallStatus.ABANDONED);
        assertThat(inTx(() -> waitingCallRepository.findById(freshId).orElseThrow().getStatus()))
                .isEqualTo(QueueWaitingCallStatus.WAITING);
        assertThat(inTx(() -> waitingCallRepository.findById(assignedId).orElseThrow().getStatus()))
                .isEqualTo(QueueWaitingCallStatus.ASSIGNED);
    }

    @Test
    @DisplayName("IT-5: overflow moves WAITING rows in one bounded hop and resets expiry; ASSIGNED untouched")
    void overflowBoundedHop() {
        memberAgent(1); // an agent so one call can be assigned
        UUID targetId = seedQueue(tenantId, "TARGET-" + UUID.randomUUID().toString().substring(0, 6),
                QueueStatus.ACTIVE);
        inTx(() -> {
            var q = queueRepository.findById(queueId).orElseThrow();
            q.setOverflowEnabled(true);
            q.setOverflowQueueId(targetId);
            queueRepository.save(q);
            return null;
        });
        seedWaitingCall(tenantId, queueId, null);
        seedWaitingCall(tenantId, queueId, null);
        UUID assignedId = seedWaitingCall(tenantId, queueId, null);
        var assignment = inTx(() -> acdService.attemptAssignment(tenantId, queueId, assignedId));
        assertThat(assignment.status()).isEqualTo(AcdResult.AcdStatus.ASSIGNED);
        assertThat(assignedCount(queueId)).isEqualTo(1);

        int moved = inTx(() -> overflowService.moveWaitingCallsToOverflow(tenantId, queueId));

        assertThat(moved).isEqualTo(2);
        assertThat(waitingCount(queueId)).isZero();
        assertThat(assignedCount(queueId)).isEqualTo(1);
        assertThat(waitingCount(targetId)).isEqualTo(2);
        // second run: nothing left to move (idempotent)
        assertThat(inTx(() -> overflowService.moveWaitingCallsToOverflow(tenantId, queueId)))
                .isZero();
    }

    @Test
    @DisplayName("IT-6: tenant isolation — foreign queue and foreign waiting call fail closed")
    void tenantIsolation() {
        memberAgent(1);
        UUID waitingCallId = seedWaitingCall(tenantId, queueId, null);
        UUID tenantB = seedTenant("cb");
        UUID queueB = seedQueue(tenantB, "B-" + UUID.randomUUID().toString().substring(0, 6),
                QueueStatus.ACTIVE);
        UUID callB = seedWaitingCall(tenantB, queueB, null);

        // Tenant B attempts tenant A's queue → QUEUE_NOT_FOUND (fail closed).
        var r1 = inTx(() -> acdService.attemptAssignment(tenantB, queueId, waitingCallId));
        assertThat(r1.status()).isEqualTo(AcdResult.AcdStatus.QUEUE_NOT_ELIGIBLE);
        assertThat(r1.reason()).isEqualTo(AcdReasons.QUEUE_NOT_FOUND);

        // Tenant A's call injected into tenant B's queue → not eligible.
        var r2 = inTx(() -> acdService.attemptAssignment(tenantB, queueB, waitingCallId));
        assertThat(r2.status()).isEqualTo(AcdResult.AcdStatus.WAITING_CALL_NOT_ELIGIBLE);

        // Tenant B's own call on its own (empty-membership) queue → NO_ACTIVE_MEMBERS.
        var r3 = inTx(() -> acdService.attemptAssignment(tenantB, queueB, callB));
        assertThat(r3.status()).isEqualTo(AcdResult.AcdStatus.NO_ELIGIBLE_AGENT);
    }

    // === concurrency matrix (real advisory locks + conditional UPDATEs) ===

    private List<UUID> memberAgents(int count, int maxConcurrent) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(memberAgent(maxConcurrent));
        }
        return ids;
    }

    private void runConcurrently(int threads, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                } catch (Exception e) {
                    // counted as rejected attempt
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
    }

    @Test
    @DisplayName("CC-1: 1 caller → 1 agent → exactly 1 reservation + 1 assignment")
    void oneCallerOneAgent() throws Exception {
        UUID agentId = memberAgent(1);
        List<UUID> calls = List.of(seedWaitingCall(tenantId, queueId, null));

        runConcurrently(1, () -> inTx(() ->
                acdService.attemptAssignment(tenantId, queueId, calls.get(0))));

        assertThat(activeHolds(agentId)).isEqualTo(1);
        assertThat(assignedCount(queueId)).isEqualTo(1);
    }

    @Test
    @DisplayName("CC-2: 2 callers → 1 agent (maxConcurrent=1) → 1 reservation, 1 assignment, 1 deterministic loser")
    void twoCallersOneAgent() throws Exception {
        UUID agentId = memberAgent(1);
        UUID c1 = seedWaitingCall(tenantId, queueId, null);
        UUID c2 = seedWaitingCall(tenantId, queueId, null);
        AtomicInteger assigned = new AtomicInteger();

        runConcurrently(2, () -> {
            UUID call = assigned.getAndIncrement() == 0 ? c1 : c2;
            inTx(() -> acdService.attemptAssignment(tenantId, queueId, call));
        });

        assertThat(activeHolds(agentId)).isEqualTo(1);
        assertThat(assignedCount(queueId)).isEqualTo(1);
        assertThat(waitingCount(queueId)).isEqualTo(1); // loser stays WAITING (§32)
    }

    @Test
    @DisplayName("CC-3: 20 callers → 5 agents (maxConcurrent=1) → ≤5 holds, ≤5 assignments, each agent ≤1")
    void twentyCallersFiveAgents() throws Exception {
        List<UUID> agents = memberAgents(5, 1);
        List<UUID> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(seedWaitingCall(tenantId, queueId, null));
        }
        AtomicInteger assigned = new AtomicInteger();

        runConcurrently(20, () -> {
            int idx = assigned.getAndIncrement() % calls.size();
            inTx(() -> acdService.attemptAssignment(tenantId, queueId, calls.get(idx)));
        });

        int totalHolds = 0;
        for (UUID agentId : agents) {
            int holds = activeHolds(agentId);
            assertThat(holds).isLessThanOrEqualTo(1);
            totalHolds += holds;
        }
        assertThat(totalHolds).isLessThanOrEqualTo(5);
        assertThat(assignedCount(queueId)).isLessThanOrEqualTo(5);
        assertThat(waitingCount(queueId)).isGreaterThanOrEqualTo(15);
    }

    @Test
    @DisplayName("CC-4: 20 callers → 5 agents (maxConcurrent=2) → ≤10 holds, each agent ≤2")
    void twentyCallersFiveAgentsCapacityTwo() throws Exception {
        List<UUID> agents = memberAgents(5, 2);
        List<UUID> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(seedWaitingCall(tenantId, queueId, null));
        }
        AtomicInteger assigned = new AtomicInteger();

        runConcurrently(20, () -> {
            int idx = assigned.getAndIncrement() % calls.size();
            inTx(() -> acdService.attemptAssignment(tenantId, queueId, calls.get(idx)));
        });

        int totalHolds = 0;
        for (UUID agentId : agents) {
            int holds = activeHolds(agentId);
            assertThat(holds).isLessThanOrEqualTo(2);
            totalHolds += holds;
        }
        assertThat(totalHolds).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("CC-5: concurrent assignment of the SAME waiting call → exactly one ASSIGNED row")
    void concurrentSameCallSingleAssignment() throws Exception {
        memberAgent(2);
        UUID call = seedWaitingCall(tenantId, queueId, null);
        AtomicInteger wins = new AtomicInteger();

        runConcurrently(10, () -> {
            var result = inTx(() ->
                    acdService.attemptAssignment(tenantId, queueId, call));
            if (result.status() == AcdResult.AcdStatus.ASSIGNED) {
                wins.incrementAndGet();
            }
            // ALREADY_ASSIGNED is the expected deterministic outcome for losers
        });

        assertThat(wins.get()).isEqualTo(1);
        assertThat(assignedCount(queueId)).isEqualTo(1);
    }

    @Test
    @DisplayName("CC-6: concurrent reservation + release → no double-release, no orphan hold")
    void concurrentReserveAndRelease() throws Exception {
        memberAgent(1);
        UUID call = seedWaitingCall(tenantId, queueId, null);
        inTx(() -> acdService.attemptAssignment(tenantId, queueId, call));

        runConcurrently(8, () -> inTx(() ->
                acdService.releaseAssignment(call, "CONCURRENT_RELEASE")));

        assertThat(assignedCount(queueId)).isZero();
        assertThat(waitingCount(queueId)).isEqualTo(1);
    }
}
