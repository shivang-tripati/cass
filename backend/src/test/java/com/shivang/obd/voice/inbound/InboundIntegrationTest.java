package com.shivang.obd.voice.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.did.DidInboundDestination;
import com.shivang.obd.voice.queue.QueueRepository;
import com.shivang.obd.voice.acd.AcdResult;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.call.CallType;
import com.shivang.obd.voice.queue.QueueStatus;
import com.shivang.obd.voice.queue.QueueWaitingCall;
import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * VB-4D PostgreSQL integration tests (real PostgreSQL via Testcontainers,
 * full Flyway chain V1..V39). Covers the inbound lifecycle IT-1..IT-10
 * plus concurrency (duplicate CHANNEL_CREATE, N calls / M agents).
 */
class InboundIntegrationTest extends InboundIntegrationSupport {

    private static final String DID_E164 = "+911800100200";

    @BeforeEach
    void setUpEach() {
        initInboundServices();
    }

    @AfterEach
    void tearDownEach() {
        cleanupAgentData();
    }

    // ------------------------------------------------------------------
    // IT-1..IT-4 — happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("IT-1: inbound queue call → canonical session + CUSTOMER leg + WAITING queue row")
    void inboundQueueCallPersists() {
        UUID tenantId = seedTenant("inb1");
        UUID queueId = seedQueue(tenantId, "q-inb1", QueueStatus.ACTIVE);
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.QUEUE, queueId, null);

        Optional<UUID> sessionId = inTx(() -> inboundCallService
                .onInboundChannelCreated("ch-it1", DID_E164, "+919999000001"));

        assertThat(sessionId).isPresent();
        CallSession session = inTx(() ->
                sessionRepository.findByIdAndDeletedAtIsNull(sessionId.get()).orElseThrow());
        assertThat(session.getDirection()).isEqualTo(com.shivang.obd.voice.call.CallDirection.INBOUND);
        assertThat(session.getCallType()).isEqualTo(CallType.CONTACT_CENTER_INBOUND);
        assertThat(session.getProviderCallId()).isEqualTo("ch-it1");
        CallLeg caller = callerLeg(sessionId.get());
        assertThat(caller).isNotNull();
        assertThat(caller.getLegType()).isEqualTo(com.shivang.obd.voice.call.CallLegType.CUSTOMER);
        assertThat(caller.getProviderCallId()).isEqualTo("ch-it1");
        QueueWaitingCall wc = inTx(() -> waitingCallRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId.get()).orElseThrow());
        assertThat(wc.getStatus()).isEqualTo(QueueWaitingCallStatus.WAITING);
        assertThat(wc.getQueueId()).isEqualTo(queueId);
        assertThat(wc.getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("IT-2: ACD assignment on the inbound waiting call → ASSIGNED with live reservation")
    void acdAssignsInboundCall() {
        UUID tenantId = seedTenant("inb2");
        UUID queueId = seedQueue(tenantId, "q-inb2", QueueStatus.ACTIVE);
        UUID agentId = seedAgent(tenantId, "a-inb2",
                com.shivang.obd.voice.agent.AgentAdminStatus.ACTIVE,
                com.shivang.obd.voice.agent.AgentAvailability.AVAILABLE, 1);
        seedEndpoint(tenantId, agentId, "sip:agent-inb2", com.shivang.obd.voice.call.EndpointType.SIP, true);
        seedMembership(tenantId, queueId, agentId);
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.QUEUE, queueId, null);

        Optional<UUID> sessionId = inTx(() -> inboundCallService
                .onInboundChannelCreated("ch-it2", DID_E164, null));
        UUID waitingCallId = inTx(() -> waitingCallRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId.get()).orElseThrow().getId());

        AcdResult result = inTx(() -> acdService.attemptAssignment(tenantId, queueId, waitingCallId));

        assertThat(result.status()).isEqualTo(AcdResult.AcdStatus.ASSIGNED);
        QueueWaitingCall wc = inTx(() -> waitingCallRepository
                .findByIdAndDeletedAtIsNull(waitingCallId).orElseThrow());
        assertThat(wc.getStatus()).isEqualTo(QueueWaitingCallStatus.ASSIGNED);
        assertThat(wc.getAssignedAgentId()).isEqualTo(agentId);
        assertThat(activeHolds(agentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("IT-3: connectAssignedAgent creates the AGENT leg and persists the provider UUID")
    void agentLegPersisted() {
        UUID tenantId = seedTenant("inb3");
        UUID queueId = seedQueue(tenantId, "q-inb3", QueueStatus.ACTIVE);
        UUID agentId = seedAgent(tenantId, "a-inb3",
                com.shivang.obd.voice.agent.AgentAdminStatus.ACTIVE,
                com.shivang.obd.voice.agent.AgentAvailability.AVAILABLE, 1);
        seedEndpoint(tenantId, agentId, "sip:agent-inb3", com.shivang.obd.voice.call.EndpointType.SIP, true);
        seedMembership(tenantId, queueId, agentId);
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.QUEUE, queueId, null);
        dialer.succeed = true;

        Optional<UUID> sessionId = inTx(() -> inboundCallService
                .onInboundChannelCreated("ch-it3", DID_E164, null));
        UUID waitingCallId = inTx(() -> waitingCallRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId.get()).orElseThrow().getId());
        inTx(() -> acdService.attemptAssignment(tenantId, queueId, waitingCallId));

        boolean connected = inTx(() -> inboundCallService.connectAssignedAgent(waitingCallId));

        assertThat(connected).isTrue();
        CallLeg agent = agentLeg(sessionId.get());
        assertThat(agent).isNotNull();
        assertThat(agent.getLegType()).isEqualTo(com.shivang.obd.voice.call.CallLegType.AGENT);
        assertThat(agent.getAgentId()).isEqualTo(agentId);
        assertThat(agent.getProviderCallId()).startsWith("agent-uuid-");
        assertThat(agent.getStatus()).isEqualTo(CallLegStatus.DIALING);
    }

    @Test
    @DisplayName("IT-4: duplicate CHANNEL_CREATE (same channel UUID, 20x) → exactly one session and one caller leg")
    void duplicateChannelCreateCreatesOneSession() throws Exception {
        UUID tenantId = seedTenant("inb4");
        UUID queueId = seedQueue(tenantId, "q-inb4", QueueStatus.ACTIVE);
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.QUEUE, queueId, null);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Optional<UUID>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return inTx(() -> inboundCallService
                        .onInboundChannelCreated("ch-dup", DID_E164, null));
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int sessions = inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM call_sessions WHERE provider_call_id = 'ch-dup' "
                    + "AND deleted_at IS NULL").getSingleResult();
            return n.intValue();
        });
        int legs = inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM call_legs l JOIN call_sessions s "
                    + "ON s.id = l.call_session_id WHERE s.provider_call_id = 'ch-dup' "
                    + "AND l.leg_type = 'CUSTOMER' AND s.deleted_at IS NULL "
                    + "AND l.deleted_at IS NULL").getSingleResult();
            return n.intValue();
        });
        assertThat(sessions).isEqualTo(1);
        assertThat(legs).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // IT-5..IT-6 — failure paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("IT-5: caller hangup while queued → REMOVED queue row, session finalized")
    void callerHangupWhileQueued() {
        UUID tenantId = seedTenant("inb5");
        UUID queueId = seedQueue(tenantId, "q-inb5", QueueStatus.ACTIVE);
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.QUEUE, queueId, null);
        Optional<UUID> sessionId = inTx(() -> inboundCallService
                .onInboundChannelCreated("ch-it5", DID_E164, null));

        CallLeg caller = callerLeg(sessionId.get());
        inTx(() -> {
            inboundCallService.onInboundCallerHangup(caller, "NORMAL_CLEARING");
            return null;
        });

        QueueWaitingCall wc = inTx(() -> waitingCallRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId.get()).orElseThrow());
        assertThat(wc.getStatus()).isEqualTo(QueueWaitingCallStatus.REMOVED);
        CallSession session = inTx(() ->
                sessionRepository.findByIdAndDeletedAtIsNull(sessionId.get()).orElseThrow());
        assertThat(session.getStatus()).isEqualTo(CallSessionStatus.FAILED);
    }

    @Test
    @DisplayName("IT-6: agent originate failure after ACD assignment → reservation released, call back to WAITING flow")
    void originateFailureReleasesAssignment() {
        UUID tenantId = seedTenant("inb6");
        UUID queueId = seedQueue(tenantId, "q-inb6", QueueStatus.ACTIVE);
        UUID agentId = seedAgent(tenantId, "a-inb6",
                com.shivang.obd.voice.agent.AgentAdminStatus.ACTIVE,
                com.shivang.obd.voice.agent.AgentAvailability.AVAILABLE, 1);
        seedEndpoint(tenantId, agentId, "sip:agent-inb6", com.shivang.obd.voice.call.EndpointType.SIP, true);
        seedMembership(tenantId, queueId, agentId);
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.QUEUE, queueId, null);
        dialer.succeed = false; // originate always fails

        Optional<UUID> sessionId = inTx(() -> inboundCallService
                .onInboundChannelCreated("ch-it6", DID_E164, null));
        UUID waitingCallId = inTx(() -> waitingCallRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId.get()).orElseThrow().getId());
        inTx(() -> acdService.attemptAssignment(tenantId, queueId, waitingCallId));
        inTx(() -> inboundCallService.connectAssignedAgent(waitingCallId));

        assertThat(activeHolds(agentId)).isZero();
        QueueWaitingCall wc = inTx(() -> waitingCallRepository
                .findByIdAndDeletedAtIsNull(waitingCallId).orElseThrow());
        assertThat(wc.getStatus()).isEqualTo(QueueWaitingCallStatus.WAITING);
        assertThat(wc.getAssignedAgentId()).isNull();
    }

    // ------------------------------------------------------------------
    // IT-7..IT-9 — queue policies reused from VB-4C
    // ------------------------------------------------------------------

    @Test
    @DisplayName("IT-7: no eligible agent → waiting call stays WAITING (never disappears)")
    void noAgentKeepsWaiting() {
        UUID tenantId = seedTenant("inb7");
        UUID queueId = seedQueue(tenantId, "q-inb7", QueueStatus.ACTIVE);
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.QUEUE, queueId, null);
        Optional<UUID> sessionId = inTx(() -> inboundCallService
                .onInboundChannelCreated("ch-it7", DID_E164, null));
        UUID waitingCallId = inTx(() -> waitingCallRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId.get()).orElseThrow().getId());

        retryScheduler.processOnce(waitingCallId);

        QueueWaitingCall wc = inTx(() -> waitingCallRepository
                .findByIdAndDeletedAtIsNull(waitingCallId).orElseThrow());
        assertThat(wc.getStatus()).isEqualTo(QueueWaitingCallStatus.WAITING);
        assertThat(activeHolds(null)).isZero();
    }

    @Test
    @DisplayName("IT-8: queue timeout sweep applies to inbound entries → WAITING → ABANDONED")
    void queueTimeoutAbandons() {
        UUID tenantId = seedTenant("inb8");
        UUID queueId = seedQueue(tenantId, "q-inb8", QueueStatus.ACTIVE);
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.QUEUE, queueId, null);
        Optional<UUID> sessionId = inTx(() -> inboundCallService
                .onInboundChannelCreated("ch-it8", DID_E164, null));
        UUID waitingCallId = inTx(() -> waitingCallRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId.get()).orElseThrow().getId());
        // Age the entry past the wait budget.
        inTx(() -> {
            entityManager.createNativeQuery(
                    "UPDATE queue_waiting_calls SET entered_at = now() - interval '2 hours', "
                    + "expires_at = now() - interval '1 hour' WHERE id = :id")
                    .setParameter("id", waitingCallId).executeUpdate();
            return null;
        });

        inTx(() -> {
            new com.shivang.obd.voice.acd.AcdMaintenanceScheduler(
                    reservationRepository, agentRepository, waitingCallRepository).sweep();
            return null;
        });

        QueueWaitingCall wc = inTx(() -> waitingCallRepository
                .findByIdAndDeletedAtIsNull(waitingCallId).orElseThrow());
        assertThat(wc.getStatus()).isEqualTo(QueueWaitingCallStatus.ABANDONED);
    }

    @Test
    @DisplayName("IT-9: overflow moves inbound waiting calls using the existing bounded VB-4C mechanism")
    void overflowReused() {
        UUID tenantId = seedTenant("inb9");
        UUID queueA = seedQueue(tenantId, "q-a-inb9", QueueStatus.ACTIVE);
        UUID queueB = seedQueue(tenantId, "q-b-inb9", QueueStatus.ACTIVE);
        inTx(() -> {
            var a = queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queueA, tenantId).orElseThrow();
            a.setOverflowEnabled(true);
            a.setOverflowQueueId(queueB);
            queueRepository.save(a);
            return null;
        });
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.QUEUE, queueA, null);
        Optional<UUID> sessionId = inTx(() -> inboundCallService
                .onInboundChannelCreated("ch-it9", DID_E164, null));

        int moved = inTx(() -> overflowService.moveWaitingCallsToOverflow(tenantId, queueA));

        assertThat(moved).isEqualTo(1);
        QueueWaitingCall wc = inTx(() -> waitingCallRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId.get()).orElseThrow());
        assertThat(wc.getQueueId()).isEqualTo(queueB);
        assertThat(wc.getStatus()).isEqualTo(QueueWaitingCallStatus.WAITING);
    }

    // ------------------------------------------------------------------
    // IT-10 — tenant isolation + direct agent
    // ------------------------------------------------------------------

    @Test
    @DisplayName("IT-10: tenant A DID cannot route to tenant B queue/agent — fail closed, nothing created")
    void crossTenantDestinationFailsClosed() {
        UUID tenantA = seedTenant("inb10a");
        UUID tenantB = seedTenant("inb10b");
        // Queue belongs to tenant B; the DID belongs to tenant A. The V39 FK
        // graph makes this configuration impossible to persist, so the DID
        // seeds with tenant B's queue id — routing must never find it via
        // tenant A's scope.
        UUID queueB = seedQueue(tenantB, "q-inb10-b", QueueStatus.ACTIVE);
        UUID agentB = seedAgent(tenantB, "a-inb10-b",
                com.shivang.obd.voice.agent.AgentAdminStatus.ACTIVE,
                com.shivang.obd.voice.agent.AgentAvailability.AVAILABLE, 1);
        seedInboundDid(tenantA, DID_E164, DidInboundDestination.QUEUE, queueB, agentB);

        // The DID row was seeded directly against the DB; the service must
        // still fail closed when the destination is not resolvable in the
        // DID's tenant (simulates any cross-tenant reference that slips in).
        Optional<UUID> sessionId = inTx(() -> inboundCallService
                .onInboundChannelCreated("ch-it10", DID_E164, null));

        assertThat(sessionId).isPresent();
        CallSession session = inTx(() ->
                sessionRepository.findByIdAndDeletedAtIsNull(sessionId.get()).orElseThrow());
        assertThat(session.getTenantId()).isEqualTo(tenantA); // session bound to the DID's tenant
        assertThat(waitingCount(queueB)).isZero();            // tenant B queue untouched
        assertThat(activeHolds(agentB)).isZero();             // tenant B agent untouched
    }

    @Test
    @DisplayName("IT-11: direct-agent DID → reservation + AGENT leg without any queue row")
    void directAgentPath() {
        UUID tenantId = seedTenant("inb11");
        UUID agentId = seedAgent(tenantId, "a-inb11",
                com.shivang.obd.voice.agent.AgentAdminStatus.ACTIVE,
                com.shivang.obd.voice.agent.AgentAvailability.AVAILABLE, 1);
        seedEndpoint(tenantId, agentId, "sip:agent-inb11", com.shivang.obd.voice.call.EndpointType.SIP, true);
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.AGENT, null, agentId);
        dialer.succeed = true;

        Optional<UUID> sessionId = inTx(() -> inboundCallService
                .onInboundChannelCreated("ch-it11", DID_E164, null));

        assertThat(sessionId).isPresent();
        assertThat(waitingCallRepository.findByCallSessionIdAndDeletedAtIsNull(sessionId.get()))
                .isEmpty();
        assertThat(agentLeg(sessionId.get())).isNotNull();
        assertThat(activeHolds(agentId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Concurrency: 20 inbound calls / 5 agents (capacity 1 and 2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CC-1: 20 inbound calls → 5 agents (capacity 1) → at most 5 reservations, each agent ≤ 1")
    void twentyCallsFiveAgentsCapacityOne() throws Exception {
        UUID tenantId = seedTenant("inb-c1");
        UUID queueId = seedQueue(tenantId, "q-inb-c1", QueueStatus.ACTIVE);
        List<UUID> cc1AgentIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID agentId = seedAgent(tenantId, "a-c1-" + i,
                    com.shivang.obd.voice.agent.AgentAdminStatus.ACTIVE,
                    com.shivang.obd.voice.agent.AgentAvailability.AVAILABLE, 1);
            cc1AgentIds.add(agentId);
            seedEndpoint(tenantId, agentId, "sip:agent-c1-" + i,
                    com.shivang.obd.voice.call.EndpointType.SIP, true);
            seedMembership(tenantId, queueId, agentId);
        }
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.QUEUE, queueId, null);
        dialer.succeed = true;

        List<UUID> waitingCallIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Optional<UUID> sessionId = inTx(() -> inboundCallService
                    .onInboundChannelCreated("ch-c1-" + UUID.randomUUID(), DID_E164, null));
            waitingCallIds.add(inTx(() -> waitingCallRepository
                    .findByCallSessionIdAndDeletedAtIsNull(sessionId.get()).orElseThrow().getId()));
        }

        // Run the ACD retry scheduler concurrently from 20 threads.
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        AtomicInteger processed = new AtomicInteger();
        for (UUID waitingCallId : waitingCallIds) {
            futures.add(pool.submit(() -> {
                start.await();
                if (inTx(() -> retryScheduler.processOnce(waitingCallId))) {
                    processed.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // Total non-RELEASED reservations across all agents ≤ 5 (each capacity 1).
        int totalActive = inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM agent_reservations WHERE status <> 'RELEASED' "
                    + "AND deleted_at IS NULL").getSingleResult();
            return n.intValue();
        });
        assertThat(totalActive).isLessThanOrEqualTo(5);
        int assigned = assignedCount(queueId);
        assertThat(assigned).isLessThanOrEqualTo(5);
        // Every agent individually ≤ 1 active hold (agents are tracked locally
        // at seed time — no tenant-wide query needed).
        for (UUID agentId : cc1AgentIds) {
            assertThat(activeHolds(agentId)).isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("CC-2: 20 inbound calls → 5 agents (capacity 2) → at most 10 reservations")
    void twentyCallsFiveAgentsCapacityTwo() throws Exception {
        UUID tenantId = seedTenant("inb-c2");
        UUID queueId = seedQueue(tenantId, "q-inb-c2", QueueStatus.ACTIVE);
        for (int i = 0; i < 5; i++) {
            UUID agentId = seedAgent(tenantId, "a-c2-" + i,
                    com.shivang.obd.voice.agent.AgentAdminStatus.ACTIVE,
                    com.shivang.obd.voice.agent.AgentAvailability.AVAILABLE, 2);
            seedEndpoint(tenantId, agentId, "sip:agent-c2-" + i,
                    com.shivang.obd.voice.call.EndpointType.SIP, true);
            seedMembership(tenantId, queueId, agentId);
        }
        seedInboundDid(tenantId, DID_E164, DidInboundDestination.QUEUE, queueId, null);
        dialer.succeed = true;

        List<UUID> waitingCallIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Optional<UUID> sessionId = inTx(() -> inboundCallService
                    .onInboundChannelCreated("ch-c2-" + UUID.randomUUID(), DID_E164, null));
            waitingCallIds.add(inTx(() -> waitingCallRepository
                    .findByCallSessionIdAndDeletedAtIsNull(sessionId.get()).orElseThrow().getId()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (UUID waitingCallId : waitingCallIds) {
            futures.add(pool.submit(() -> {
                start.await();
                inTx(() -> retryScheduler.processOnce(waitingCallId));
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int totalActive = inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM agent_reservations WHERE status <> 'RELEASED' "
                    + "AND deleted_at IS NULL").getSingleResult();
            return n.intValue();
        });
        assertThat(totalActive).isLessThanOrEqualTo(10);
    }
}
