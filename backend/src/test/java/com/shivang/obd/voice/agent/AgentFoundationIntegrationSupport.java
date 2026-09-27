package com.shivang.obd.voice.agent;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.voice.call.CallDirection;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.capacity.AgentConnectIntegrationSupport;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.mockito.Mockito;

/**
 * VB-4A integration support: extends the shared real-PostgreSQL harness
 * (Testcontainers, full Flyway chain V1..V36, {@code @DataJpaTest} slice)
 * and adds call-leg seeding plus the directory/call-query services built
 * over real repositories. Authorization is a no-op mock here — the focus
 * is persistence, derivation and tenant-scoped queries, not RBAC
 * (capability wiring is covered by the unit and slice tests).
 *
 * <p>Tests run with the test-managed transaction DISABLED
 * ({@code NOT_SUPPORTED}); service calls are wrapped in {@link #inTx}
 * so JPA dirty checking and explicit saves commit real transactions.</p>
 */
public abstract class AgentFoundationIntegrationSupport
        extends AgentConnectIntegrationSupport {

    @org.springframework.beans.factory.annotation.Autowired
    protected CallLegRepository callLegRepository;

    protected AgentDirectoryService directoryService;
    protected AgentCallQueryService callQueryService;

    /** Builds the VB-4A services over real repositories + no-op authz. */
    protected void initServices() {
        AuthorizationService noopAuthz = Mockito.mock(AuthorizationService.class);
        Mockito.doNothing().when(noopAuthz).requireCapability(
                Mockito.any(), Mockito.anyString(), Mockito.any());
        CurrentUserProvider fixedUser = Mockito.mock(CurrentUserProvider.class);
        Mockito.when(fixedUser.current()).thenReturn(Optional.of(
                new AuthenticatedUser(
                        UUID.fromString("77777777-0000-4000-8000-000000000001"),
                        "ops@example.com", null)));

        directoryService = new AgentDirectoryService(agentRepository, endpointRepository,
                reservationRepository, callLegRepository, tenantRepository,
                noopAuthz, fixedUser);
        callQueryService = new AgentCallQueryService(agentRepository, callLegRepository,
                callSessionRepository, tenantRepository, noopAuthz, fixedUser);
    }

    /** Seeds an AGENT call leg for a session (active or terminal). */
    public UUID seedAgentLeg(UUID tenantId, UUID agentId, UUID sessionId,
                             CallLegStatus status, Instant initiatedAt,
                             Instant answeredAt, Instant endedAt,
                             String failureCode) {
        return inTx(() -> {
            CallLeg leg = new CallLeg();
            leg.setCallSessionId(sessionId);
            leg.setLegType(CallLegType.AGENT);
            leg.setEndpointType(com.shivang.obd.voice.call.EndpointType.SIP);
            leg.setDirection(CallDirection.OUTBOUND);
            leg.setStatus(status);
            leg.setAgentId(agentId);
            leg.setTarget("sip:agent@pbx.example");
            leg.setProviderCallId("agent-" + UUID.randomUUID());
            leg.setInitiatedAt(initiatedAt);
            leg.setAnsweredAt(answeredAt);
            leg.setEndedAt(endedAt);
            leg.setFailureCode(failureCode);
            return callLegRepository.save(leg).getId();
        });
    }

    /** Seeds an ended (terminal) call session. */
    public UUID seedEndedCallSession(UUID tenantId, CallSessionStatusHolder holder) {
        return inTx(() -> {
            CallSession session = new CallSession();
            session.setTenantId(tenantId);
            session.setStatus(holder.status());
            session.setDirection(CallDirection.OUTBOUND);
            session.setCallType(com.shivang.obd.voice.call.CallType.VOICE_BLAST);
            session.setInitiatedAt(holder.initiatedAt());
            session.setEndedAt(holder.endedAt());
            session.setDestinationNumber("+15550001111");
            session.setProviderCallId("caller-" + UUID.randomUUID());
            return callSessionRepository.save(session).getId();
        });
    }

    /** Parameter carrier for session seeding (records cannot be pre-Java-16 here). */
    public record CallSessionStatusHolder(
            com.shivang.obd.voice.call.CallSessionStatus status,
            Instant initiatedAt,
            Instant endedAt) {
    }

    /** Clears all test agent/call data in FK-safe order. */
    @Override
    public void cleanupAgentData() {
        inTx(() -> {
            entityManager.createNativeQuery("DELETE FROM agent_reservations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM agent_endpoints").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM agents").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM call_legs").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM call_sessions").executeUpdate();
            return null;
        });
    }
}
