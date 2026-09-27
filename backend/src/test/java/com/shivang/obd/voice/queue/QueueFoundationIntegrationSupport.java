package com.shivang.obd.voice.queue;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.voice.capacity.AgentConnectIntegrationSupport;
import java.util.Optional;
import java.util.UUID;
import org.mockito.Mockito;

/**
 * VB-4B integration support: extends the shared real-PostgreSQL harness
 * (Testcontainers, full Flyway chain V1..V37, {@code @DataJpaTest} slice)
 * and builds the {@link QueueDirectoryService} over real repositories +
 * a real EntityManager (the membership advisory lock runs real
 * {@code pg_try_advisory_xact_lock} SQL). Authorization is a no-op mock —
 * persistence, constraints, isolation and concurrency are the focus here;
 * capability wiring is covered by the unit/slice tests.
 */
public abstract class QueueFoundationIntegrationSupport
        extends AgentConnectIntegrationSupport {

    @org.springframework.beans.factory.annotation.Autowired
    protected QueueRepository queueRepository;
    @org.springframework.beans.factory.annotation.Autowired
    protected QueueMembershipRepository membershipRepository;
    @org.springframework.beans.factory.annotation.Autowired
    protected QueueWaitingCallRepository waitingCallRepository;

    protected QueueDirectoryService queueService;

    /** Builds the VB-4B service over real repositories + no-op authz. */
    protected void initQueueService() {
        AuthorizationService noopAuthz = Mockito.mock(AuthorizationService.class);
        Mockito.doNothing().when(noopAuthz).requireCapability(
                Mockito.any(), Mockito.anyString(), Mockito.any());
        CurrentUserProvider fixedUser = Mockito.mock(CurrentUserProvider.class);
        Mockito.when(fixedUser.current()).thenReturn(Optional.of(
                new AuthenticatedUser(
                        UUID.fromString("77777777-0000-4000-8000-000000000001"),
                        "ops@example.com", null)));

        queueService = new QueueDirectoryService(queueRepository, membershipRepository,
                waitingCallRepository, agentRepository, tenantRepository,
                noopAuthz, fixedUser, entityManager);
    }

    /** Clears all test queue/agent/call data in FK-safe order. */
    @Override
    public void cleanupAgentData() {
        inTx(() -> {
            entityManager.createNativeQuery(
                    "DELETE FROM queue_waiting_calls").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM queue_memberships").executeUpdate();
            entityManager.createNativeQuery(
                    "UPDATE queues SET overflow_queue_id = NULL").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM queues").executeUpdate();
            // The VB-3-level cleanup does not remove call legs; in the full
            // suite leftover legs (from DB-backed lifecycle suites sharing
            // the container) block the call_sessions delete via FK.
            entityManager.createNativeQuery(
                    "DELETE FROM call_legs").executeUpdate();
            return null;
        });
        super.cleanupAgentData();
    }
}
