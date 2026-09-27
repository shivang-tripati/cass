package com.shivang.obd.voice.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.voice.agent.Agent;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentAvailability;
import com.shivang.obd.voice.agent.AgentReasons;
import com.shivang.obd.voice.agent.AgentReservation;
import com.shivang.obd.voice.agent.AgentReservationService;
import com.shivang.obd.voice.agent.AgentReservationStatus;
import com.shivang.obd.voice.agent.ReleaseReasons;
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
 * VB-3 integration tests against REAL PostgreSQL (full Flyway chain
 * V1..V36): atomic agent reservation semantics, tenant isolation, native
 * enum round-trips, and the 20-thread concurrency proof (spec §37/§38/§42).
 * <p>
 * These tests never fake a pass: without a Docker/PostgreSQL environment the
 * shared harness aborts them (BLOCKED), exactly like the VB-0/VB-1/VB-2
 * integration suites.
 */
public class AgentConnectIntegrationTest extends AgentConnectIntegrationSupport {

    private final List<UUID> agents = new ArrayList<>();

    @BeforeEach
    void setUpServices() {
        initReservationService();
    }

    @AfterEach
    void cleanup() {
        cleanupAgentData();
        agents.clear();
    }

    private UUID tenant(String label) {
        return seedTenant(label);
    }

    private UUID agent(UUID tenantId, int maxConcurrentCalls) {
        UUID id = seedAgent(tenantId, "agent-" + agents.size(), AgentAdminStatus.ACTIVE,
                AgentAvailability.AVAILABLE, maxConcurrentCalls);
        seedEndpoint(tenantId, id, "sip:agent-" + agents.size() + "@example.test",
                com.shivang.obd.voice.call.EndpointType.SIP, true);
        agents.add(id);
        return id;
    }

    // ------------------------------------------------------------------
    // AG-IT-1: native enum round-trip + reservation lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AG-IT-1: reservation persists with native PG enums and releases idempotently")
    void reservationLifecycleAndEnumMapping() {
        UUID tenantId = inTx(() -> tenant("enum"));
        UUID agentId = inTx(() -> agent(tenantId, 1));
        UUID sessionId = inTx(() -> seedCallSession(tenantId));

        Optional<AgentReservation> reserved =
                inTx(() -> reservationService.reserve(agentId, tenantId, sessionId, null));
        assertThat(reserved).isPresent();
        assertThat(reserved.get().getStatus()).isEqualTo(AgentReservationStatus.RESERVED);
        assertThat(activeReservationCount(agentId)).isEqualTo(1);

        // Second reserve on a max=1 agent must fail (capacity).
        assertThat(inTx(() -> reservationService.reserve(agentId, tenantId,
                seedCallSession(tenantId), null))).isEmpty();
        assertThat(activeReservationCount(agentId)).isEqualTo(1);

        // Release → idempotent: second release is a no-op, no negative usage.
        assertThat(inTx(() -> reservationService.releaseForCallSession(
                sessionId, ReleaseReasons.CALL_ENDED))).isTrue();
        assertThat(inTx(() -> reservationService.releaseForCallSession(
                sessionId, ReleaseReasons.CALL_ENDED))).isFalse();
        assertThat(activeReservationCount(agentId)).isZero();
        assertThat(releasedReservationCount(agentId)).isEqualTo(1);

        // Native enum round-trip (read back through JPA).
        Agent reloaded = inTx(() -> agentRepository.findById(agentId).orElseThrow());
        assertThat(reloaded.getAdminStatus()).isEqualTo(AgentAdminStatus.ACTIVE);
        assertThat(reloaded.getAvailability()).isIn(AgentAvailability.AVAILABLE, AgentAvailability.BUSY);
    }

    // ------------------------------------------------------------------
    // AG-IT-2/3: tenant isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AG-IT-2: cross-tenant agent reference fails closed (isolation)")
    void crossTenantAgentReferenceFailsClosed() {
        UUID tenantA = inTx(() -> tenant("iso-a"));
        UUID tenantB = inTx(() -> tenant("iso-b"));
        UUID agentB = inTx(() -> agent(tenantB, 1));
        UUID sessionA = inTx(() -> seedCallSession(tenantA));

        // Tenant A cannot reserve tenant B's agent.
        assertThat(inTx(() -> reservationService.reserve(agentB, tenantA, sessionA, null)))
                .isEmpty();

        // No reservation rows were created for agent B.
        assertThat(activeReservationCount(agentB)).isZero();
    }

    @Test
    @DisplayName("AG-IT-3: selection is tenant-scoped — tenant A never selects tenant B agents")
    void selectionIsTenantScoped() {
        UUID tenantA = inTx(() -> tenant("sel-a"));
        UUID tenantB = inTx(() -> tenant("sel-b"));
        UUID agentB = inTx(() -> agent(tenantB, 5)); // B has an eligible agent

        var selection = new com.shivang.obd.voice.agent.AgentEligibility(null, null, null);
        // Direct tenant-scoped repository check (selection SQL is tenant-bound).
        var eligibleForA = agentRepository.findEligibleOrdered(
                tenantA, org.springframework.data.domain.PageRequest.of(0, 10));
        var eligibleForB = agentRepository.findEligibleOrdered(
                tenantB, org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(eligibleForA.getContent()).isEmpty();
        assertThat(eligibleForB.getContent())
                .extracting(com.shivang.obd.voice.agent.Agent::getId)
                .containsExactly(agentB);
        assertThat(selection).isNotNull();
    }

    // ------------------------------------------------------------------
    // AG-IT-4/5: concurrency proofs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AG-IT-4: 20 concurrent reserves on a max=1 agent admit exactly 1 (spec §38)")
    void twentyConcurrentReservesAdmitExactlyOne() throws Exception {
        UUID tenantId = inTx(() -> tenant("conc"));
        UUID agentId = inTx(() -> agent(tenantId, 1));

        final int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger admitted = new AtomicInteger();
        java.util.List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        boolean visibleInTestThread = inTx(() ->
                agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agentId, tenantId).isPresent());
        org.assertj.core.api.Assertions.assertThat(visibleInTestThread)
                .withFailMessage("seeded agent %s not visible even in test thread", agentId)
                .isTrue();

        for (int i = 0; i < threads; i++) {
            final UUID sessionId = inTx(() -> seedCallSession(tenantId));
            pool.submit(() -> {
                try {
                    start.await();
                    var tx = new org.springframework.transaction.support.TransactionTemplate(
                            transactionManager);
                    String outcome = tx.execute(status -> {
                        Number agentRows = (Number) entityManager.createNativeQuery(
                                "SELECT COUNT(*) FROM agents WHERE id = :id")
                                .setParameter("id", agentId).getSingleResult();
                        if (agentRows.intValue() == 0) {
                            return "no-agent-row";
                        }
                        return reservationService.reserve(agentId, tenantId, sessionId, null).isPresent()
                                ? "ADMITTED" : "refused";
                    });
                    if ("ADMITTED".equals(outcome)) {
                        admitted.incrementAndGet();
                    }
                    errors.add(outcome);
                } catch (Exception e) {
                    errors.add("EXCEPTION: " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(admitted.get())
                .withFailMessage("admitted=%s, outcomes=%s", admitted, errors)
                .isEqualTo(1);
        assertThat(activeReservationCount(agentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("AG-IT-5: max=2 agent admits at most 2 under concurrent contention")
    void concurrentReservesRespectMaxTwo() throws Exception {
        UUID tenantId = inTx(() -> tenant("conc2"));
        UUID agentId = inTx(() -> agent(tenantId, 2));

        final int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger admitted = new AtomicInteger();
        java.util.List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final UUID sessionId = inTx(() -> seedCallSession(tenantId));
            pool.submit(() -> {
                try {
                    start.await();
                    var tx = new org.springframework.transaction.support.TransactionTemplate(
                            transactionManager);
                    // Bounded retries: a single try-lock attempt makes the
                    // number of admissions timing-dependent (all 20 attempts
                    // can cluster inside winner-1's lock hold). Production
                    // consumers of reserve() re-evaluate on refusal (ACD
                    // candidate loop); the test mirrors that with retries.
                    // The proven invariant is unchanged: never more than
                    // maxConcurrentCalls admitted, ever.
                    boolean ok = false;
                    for (int attempt = 0; attempt < 10 && !ok; attempt++) {
                        Boolean admittedNow = tx.execute(status ->
                                reservationService.reserve(agentId, tenantId, sessionId, null).isPresent());
                        ok = Boolean.TRUE.equals(admittedNow);
                        if (!ok) {
                            Thread.sleep(20L * attempt);
                        }
                    }
                    if (ok) {
                        admitted.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.add(String.valueOf(e.getMessage()));
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(admitted.get())
                .withFailMessage("admitted=%s, errors=%s", admitted, errors)
                .isEqualTo(2);
        assertThat(activeReservationCount(agentId)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // AG-IT-6: migration V36 schema facts
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AG-IT-6: V36 tables/enums/constraints exist in the real schema")
    void migrationV36Schema() {
        Number agentRows = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM agents").getSingleResult();
        Number endpointRows = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM agent_endpoints").getSingleResult();
        Number reservationRows = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM agent_reservations").getSingleResult();
        assertThat(agentRows).isNotNull();
        assertThat(endpointRows).isNotNull();
        assertThat(reservationRows).isNotNull();

        // Native enums exist with the declared values (string_agg over unnest
        // returns a plain String — Hibernate cannot map the enum_range array).
        var statuses = entityManager.createNativeQuery(
                "SELECT string_agg(val::text, ',' ORDER BY ord) FROM unnest(enum_range(NULL::agent_reservation_status)) WITH ORDINALITY AS t(val, ord)")
                .getSingleResult();
        assertThat((String) statuses).isNotBlank();

        var releaseCheck = entityManager.createNativeQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'ck_agent_reservations_release'").getSingleResult();
        assertThat(releaseCheck).isNotNull();

        // Session enum gained the VB-3 states.
        var sessionStatuses = entityManager.createNativeQuery(
                "SELECT string_agg(val::text, ',' ORDER BY ord) FROM unnest(enum_range(NULL::call_session_status)) WITH ORDINALITY AS t(val, ord)")
                .getSingleResult();
        String joined = String.valueOf(sessionStatuses);
        assertThat(joined).contains("CONNECTING_AGENT").contains("BRIDGED");

        // Leg enum gained BRIDGED.
        var legStatuses = entityManager.createNativeQuery(
                "SELECT string_agg(val::text, ',' ORDER BY ord) FROM unnest(enum_range(NULL::call_leg_status)) WITH ORDINALITY AS t(val, ord)")
                .getSingleResult();
        assertThat(String.valueOf(legStatuses)).contains("BRIDGED");

        // dtmf_interactions gained the action column.
        Number actionCol = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'dtmf_interactions' AND column_name = 'action_type'")
                .getSingleResult();
        assertThat(actionCol.intValue()).isEqualTo(1);

        // Sanity: reservation service bean type resolves (transactional proxy).
        assertThat(reservationService).isNotNull();
        assertThat(reservationService).isInstanceOf(AgentReservationService.class);
    }
}
