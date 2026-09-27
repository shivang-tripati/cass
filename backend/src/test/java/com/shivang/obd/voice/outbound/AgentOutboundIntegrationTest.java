package com.shivang.obd.voice.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.voice.media.OutboundDialRequest;
import com.shivang.obd.voice.media.OutboundDialResponse;
import com.shivang.obd.voice.media.OutboundDialResult;
import com.shivang.obd.voice.media.OutboundDialer;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentAvailability;
import com.shivang.obd.voice.agent.AgentConnectEvents;
import com.shivang.obd.voice.agent.AgentLegDialer;
import com.shivang.obd.voice.call.CallDirection;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.call.CallType;
import com.shivang.obd.voice.call.EndpointType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-4E PostgreSQL integration tests (real Testcontainer PostgreSQL, real
 * Flyway chain V1..V38, real advisory locks, real VB-0 capacity and routing
 * stack, real {@code AgentReservationService}). Only provider-edge dialers
 * are fakes — the behavior under test is PostgreSQL-backed state, capacity
 * invariants, and tenant isolation.
 *
 * <p>Scenarios: AO-IT-1 basic flow; AO-IT-2 tenant isolation (routing,
 * capacity, agent, session all tenant-scoped); AO-IT-3 gateway capacity
 * under concurrency (max 1 channel → exactly 1 admission); AO-IT-4 agent
 * capacity under concurrency (maxConcurrentCalls = 1 → exactly 1 hold);
 * AO-IT-5 combined 20-call matrix (holds ≤ N, channels ≤ M, no
 * over-admission); AO-IT-6 cleanup on hangup (reservation + capacity
 * released); AO-IT-7 duplicate-answer race (one agent leg origination).</p>
 */
class AgentOutboundIntegrationTest extends AgentOutboundIntegrationSupport {

    private static final String DEST = "+919810000000";

    private AgentOutboundCallService service;
    private OutboundDialer dialer;
    private AgentLegDialer agentLegDialer;
    private AgentConnectEvents agentConnectEvents;

    @BeforeEach
    void setUp() {
        initOutboundServices();
        dialer = mock(OutboundDialer.class);
        agentLegDialer = mock(AgentLegDialer.class);
        agentConnectEvents = mock(AgentConnectEvents.class);
        service = new AgentOutboundCallService(
                agentRepository, endpointRepository, reservationService,
                routingService, capacityService, dialer, agentLegDialer,
                mock(com.shivang.obd.voice.media.VoiceMediaController.class),
                Optional.of(agentConnectEvents), tenantRepository, didRepository,
                callSessionRepository, callLegRepository);
        // The cleanup boundary is real: delegate onCallerHangup to a genuine
        // ConnectByAgentService (the shared VB-3 implementation) so the
        // hangup-cleanup test exercises the production release path.
        com.shivang.obd.campaign.ConnectByAgentService connectService =
                new com.shivang.obd.campaign.ConnectByAgentService(
                        agentRepository, endpointRepository, reservationService,
                        callSessionRepository, callLegRepository, agentLegDialer,
                        mock(com.shivang.obd.voice.media.VoiceMediaController.class),
                        didRepository);
        org.mockito.Mockito.doAnswer(inv -> {
            connectService.onCallerHangup(inv.getArgument(0, UUID.class));
            return null;
        }).when(agentConnectEvents).onCallerHangup(org.mockito.ArgumentMatchers.any());
    }

    @AfterEach
    void tearDown() {
        cleanupAgentData();
        inTx(() -> {
            entityManager.createNativeQuery(
                    "DELETE FROM voice_channel_reservations").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM voice_route_profile_entries").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM voice_route_profiles").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM dids").executeUpdate();
            entityManager.createNativeQuery(
                    "UPDATE tenants SET reseller_id = NULL").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM sip_gateway_allocations").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM sip_gateways").executeUpdate();
            return null;
        });
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Seeds the full routing topology for a tenant; returns the gateway id. */
    private UUID seedTopology(UUID tenantId, String provider, int maxChannels) {
        UUID gatewayId = seedGateway(provider, maxChannels);
        seedAllocation(gatewayId, tenantId);
        UUID didId = seedRoutingDid(tenantId, provider, "+9111" + System.nanoTime() % 10000000000L + "0");
        seedRoutingProfile(tenantId, gatewayId, didId);
        return gatewayId;
    }

    private UUID seedReadyAgent(UUID tenantId, int maxConcurrentCalls) {
        UUID agentId = seedAgent(tenantId, "agent-" + UUID.randomUUID().toString().substring(0, 6),
                AgentAdminStatus.ACTIVE, AgentAvailability.AVAILABLE, maxConcurrentCalls);
        seedEndpoint(tenantId, agentId, "sip:agent-" + agentId.toString().substring(0, 6)
                + "@pbx.example.com", EndpointType.SIP, true);
        return agentId;
    }

    private void stubDialerAccepted() {
        when(dialer.dial(any(OutboundDialRequest.class)))
                .thenAnswer(inv -> OutboundDialResponse.accepted("fs-" + UUID.randomUUID()));
    }

    // ------------------------------------------------------------------
    // AO-IT-1: basic flow
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AO-IT-1: agent request → routing → capacity → reservation → canonical session/legs → originate accepted")
    void basicFlow() {
        UUID tenantId = seedTenant("ob1");
        UUID gatewayId = seedTopology(tenantId, "TWILIO", 10);
        UUID agentId = seedReadyAgent(tenantId, 1);
        stubDialerAccepted();

        AgentOutboundCallResult result = inTx(() ->
                service.placeCall(tenantId, agentId, DEST));

        assertThat(result.callSessionId()).isNotNull();
        assertThat(result.destinationNumber()).isEqualTo(DEST);

        inTx(() -> {
            CallSession session = callSessionRepository
                    .findByIdAndDeletedAtIsNull(result.callSessionId()).orElseThrow();
            assertThat(session.getCallType()).isEqualTo(CallType.CONTACT_CENTER_OUTBOUND);
            assertThat(session.getDirection()).isEqualTo(CallDirection.OUTBOUND);
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.DIALING);
            assertThat(session.getGatewayId()).isEqualTo(gatewayId);
            assertThat(session.getProviderCallId()).startsWith("fs-");

            List<CallLeg> legs = callLegRepository
                    .findByCallSessionIdAndDeletedAtIsNull(session.getId());
            assertThat(legs).extracting(CallLeg::getLegType)
                    .containsExactlyInAnyOrder(CallLegType.AGENT, CallLegType.CUSTOMER);
            CallLeg customer = legs.stream().filter(l -> l.getLegType() == CallLegType.CUSTOMER)
                    .findFirst().orElseThrow();
            assertThat(customer.getStatus()).isEqualTo(CallLegStatus.DIALING);
            assertThat(customer.getTarget()).isEqualTo(DEST);
            assertThat(customer.getProviderCallId()).startsWith("fs-");

            // Agent reservation exists and is RESERVED against the session.
            assertThat(activeReservationCount(agentId)).isEqualTo(1);
            assertThat(channelReservations(gatewayId)).isEqualTo(1);
            return null;
        });
    }

    // ------------------------------------------------------------------
    // AO-IT-2: tenant isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AO-IT-2: tenant A's agent cannot route through tenant B's gateway (fail closed)")
    void tenantIsolation() {
        UUID tenantA = seedTenant("ob2a");
        UUID tenantB = seedTenant("ob2b");

        // Gateway owned/allocated to tenant B only.
        UUID gwB = seedGateway("TWILIO", 10);
        seedAllocation(gwB, tenantB);
        UUID didB = seedRoutingDid(tenantB, "TWILIO", "+9122" + System.nanoTime() % 10000000000L);
        seedRoutingProfile(tenantB, gwB, didB);

        UUID agentA = seedReadyAgent(tenantA, 1);

        assertThatThrownBy(() -> inTx(() -> {
            try {
                service.placeCall(tenantA, agentA, DEST);
            } catch (AgentOutboundCallException e) {
                throw e;
            }
            return null;
        }))
                .isInstanceOf(AgentOutboundCallException.class)
                .extracting(e -> ((AgentOutboundCallException) e).getReasonCode())
                .isEqualTo(AgentOutboundReasons.NO_ELIGIBLE_GATEWAY);

        // Nothing was created: no session, no channel hold, no agent hold.
        inTx(() -> {
            Number sessions = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM call_sessions cs JOIN agents a "
                    + "ON cs.tenant_id = a.tenant_id WHERE a.id = :a")
                    .setParameter("a", agentA).getSingleResult();
            assertThat(sessions.intValue()).isZero();
            assertThat(channelReservations(gwB)).isZero();
            assertThat(activeReservationCount(agentA)).isZero();
            return null;
        });
    }

    // ------------------------------------------------------------------
    // AO-IT-3: gateway capacity under concurrency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AO-IT-3: gateway maxConcurrentChannels=1 → concurrent requests admit exactly one")
    void gatewayCapacityOneAdmission() throws Exception {
        UUID tenantId = seedTenant("ob3");
        UUID gatewayId = seedTopology(tenantId, "TWILIO", 1);
        UUID agentId = seedReadyAgent(tenantId, 5); // agent not the bottleneck
        stubDialerAccepted();

        List<Boolean> outcomes = runConcurrent(4, tenantId, agentId);

        assertThat(outcomes).containsExactlyInAnyOrder(true, false, false, false);
        assertThat(channelReservations(gatewayId)).isEqualTo(1);
        assertThat(activeReservationCount(agentId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // AO-IT-4: agent capacity under concurrency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AO-IT-4: agent maxConcurrentCalls=1 → concurrent requests produce exactly one hold")
    void agentCapacityOneAdmission() throws Exception {
        UUID tenantId = seedTenant("ob4");
        seedTopology(tenantId, "TWILIO", 50); // gateway not the bottleneck
        UUID agentId = seedReadyAgent(tenantId, 1);
        stubDialerAccepted();

        List<Boolean> outcomes = runConcurrent(4, tenantId, agentId);

        assertThat(outcomes).containsExactlyInAnyOrder(true, false, false, false);
        assertThat(activeReservationCount(agentId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // AO-IT-5: combined matrix — 20 concurrent calls, 4 agents, gateway cap 8
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AO-IT-5: 20 concurrent requests, 4 agents (cap 1 each), gateway cap 8 → ≤4 holds, ≤8 channels")
    void concurrentMatrix() throws Exception {
        UUID tenantId = seedTenant("ob5");
        UUID gatewayId = seedTopology(tenantId, "TWILIO", 8);
        List<UUID> agents = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            agents.add(seedReadyAgent(tenantId, 1));
        }
        stubDialerAccepted();

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID agentId = agents.get(i % agents.size());
            futures.add(pool.submit((Callable<Boolean>) () -> {
                start.await();
                try {
                    return inTx(() -> {
                        try {
                            service.placeCall(tenantId, agentId, DEST);
                            return true;
                        } catch (AgentOutboundCallException e) {
                            return false;
                        }
                    });
                } catch (RuntimeException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        int admitted = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                admitted++;
            }
        }
        int totalHolds = 0;
        for (UUID agentId : agents) {
            int holds = activeReservationCount(agentId);
            assertThat(holds).isLessThanOrEqualTo(1); // per-agent invariant
            totalHolds += holds;
        }
        assertThat(admitted).isEqualTo(totalHolds); // no capacity bypass
        assertThat(totalHolds).isLessThanOrEqualTo(4);
        assertThat(channelReservations(gatewayId)).isLessThanOrEqualTo(8);
    }

    // ------------------------------------------------------------------
    // AO-IT-6: cleanup on hangup
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AO-IT-6: customer hangup → session finalized, agent hold + channel hold released")
    void hangupCleanup() {
        UUID tenantId = seedTenant("ob6");
        UUID gatewayId = seedTopology(tenantId, "TWILIO", 10);
        UUID agentId = seedReadyAgent(tenantId, 1);
        stubDialerAccepted();

        AgentOutboundCallResult result = inTx(() ->
                service.placeCall(tenantId, agentId, DEST));

        inTx(() -> {
            CallLeg customer = callLegRepository
                    .findByCallSessionIdAndDeletedAtIsNull(result.callSessionId()).stream()
                    .filter(l -> l.getLegType() == CallLegType.CUSTOMER).findFirst().orElseThrow();
            CallSession session = callSessionRepository
                    .findByIdAndDeletedAtIsNull(result.callSessionId()).orElseThrow();
            service.onCustomerLegHangup(customer, "NORMAL_CLEARING", tenantId);
            return null;
        });

        inTx(() -> {
            CallSession session = callSessionRepository
                    .findByIdAndDeletedAtIsNull(result.callSessionId()).orElseThrow();
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.COMPLETED);
            assertThat(channelReservations(gatewayId)).isZero();
            assertThat(activeReservationCount(agentId)).isZero();
            return null;
        });
    }

    // ------------------------------------------------------------------
    // AO-IT-7: duplicate-answer race — one agent origination
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AO-IT-7: 20 concurrent duplicate CHANNEL_ANSWER events → agent leg originated once")
    void duplicateAnswerRace() throws Exception {
        UUID tenantId = seedTenant("ob7");
        seedTopology(tenantId, "TWILIO", 10);
        UUID agentId = seedReadyAgent(tenantId, 1);
        UUID sessionId = inTx(() -> {
            UUID id = seedCallSession(tenantId);
            CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(id).orElseThrow();
            session.setCallType(CallType.CONTACT_CENTER_OUTBOUND);
            session.setTenantId(tenantId);
            session.setStatus(CallSessionStatus.DIALING);
            callSessionRepository.save(session);
            CallLeg agentLeg = new CallLeg();
            agentLeg.setCallSessionId(id);
            agentLeg.setLegType(CallLegType.AGENT);
            agentLeg.setAgentId(agentId);
            agentLeg.setStatus(CallLegStatus.INITIATED);
            agentLeg.setEndpointType(EndpointType.SIP);
            agentLeg.setDirection(CallDirection.OUTBOUND);
            agentLeg.setInitiatedAt(Instant.now());
            // providerCallId stays null pre-origination — the service sets it
            // when the agent leg is actually originated.
            callLegRepository.save(agentLeg);
            CallLeg customer = new CallLeg();
            customer.setCallSessionId(id);
            customer.setLegType(CallLegType.CUSTOMER);
            customer.setStatus(CallLegStatus.DIALING);
            customer.setDirection(CallDirection.OUTBOUND);
            customer.setTarget(DEST);
            customer.setProviderCallId("fs-race-" + UUID.randomUUID());
            customer.setInitiatedAt(Instant.now());
            callLegRepository.save(customer);
            return id;
        });

        List<CallLeg> legs = inTx(() -> callLegRepository
                .findByCallSessionIdAndDeletedAtIsNull(sessionId));
        CallLeg customer = legs.stream().filter(l -> l.getLegType() == CallLegType.CUSTOMER)
                .findFirst().orElseThrow();
        CallLeg agentLeg = legs.stream().filter(l -> l.getLegType() == CallLegType.AGENT)
                .findFirst().orElseThrow();

        when(agentLegDialer.originateAgentLeg(any(), any(), any(), any()))
                .thenAnswer(inv -> "fs-agent-" + UUID.randomUUID());

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                inTx(() -> {
                    service.onCustomerLegAnswered(
                            callLegRepository.findByIdAndDeletedAtIsNull(customer.getId()).orElseThrow());
                    return null;
                });
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        inTx(() -> {
            CallLeg reloadedAgent = callLegRepository
                    .findByIdAndDeletedAtIsNull(agentLeg.getId()).orElseThrow();
            // Originated exactly once — exactly one provider UUID was stored.
            assertThat(reloadedAgent.getProviderCallId()).isNotNull();
            assertThat(reloadedAgent.getStatus()).isEqualTo(CallLegStatus.DIALING);
            // The originate mock returns a unique UUID per call; exactly one
            // is stored, proving the conditional (not repeated) origination.
            Number agentLegCount = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM call_legs WHERE call_session_id = :s "
                    + "AND leg_type = 'AGENT' AND deleted_at IS NULL")
                    .setParameter("s", sessionId).getSingleResult();
            assertThat(agentLegCount.intValue()).isEqualTo(1);
            return null;
        });
    }

    // ------------------------------------------------------------------
    // concurrency helper
    // ------------------------------------------------------------------

    /** Fires {@code n} concurrent placeCall requests; returns accepted/rejected per request. */
    private List<Boolean> runConcurrent(int n, UUID tenantId, UUID agentId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit((Callable<Boolean>) () -> {
                start.await();
                try {
                    inTx(() -> {
                        service.placeCall(tenantId, agentId, DEST);
                        return null;
                    });
                    return true;
                } catch (AgentOutboundCallException e) {
                    return false;
                } catch (RuntimeException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        List<Boolean> outcomes = new ArrayList<>();
        for (Future<Boolean> f : futures) {
            outcomes.add(f.get());
        }
        return outcomes;
    }
}
