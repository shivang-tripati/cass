package com.shivang.obd.voice.capacity;

import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.agent.Agent;
import com.shivang.obd.voice.agent.AgentAdminStatus;
import com.shivang.obd.voice.agent.AgentAvailability;
import com.shivang.obd.voice.agent.AgentEndpointEntity;
import com.shivang.obd.voice.agent.AgentEndpointRepository;
import com.shivang.obd.voice.agent.AgentRepository;
import com.shivang.obd.voice.agent.AgentReservation;
import com.shivang.obd.voice.agent.AgentReservationRepository;
import com.shivang.obd.voice.agent.AgentReservationService;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared seeding helpers for the VB-3 agent-connect integration tests.
 * <p>
 * Extends the shared real-PostgreSQL harness (Testcontainers
 * postgres:16-alpine, full Flyway chain V1..V36, {@code @DataJpaTest} slice:
 * real repositories, real EntityManager, real advisory locks). Provider-edge
 * collaborators (agent dialer, media controller) are Mockito fakes — the
 * behavior under test here is PostgreSQL concurrency/persistence, not ESL.
 * <p>
 * Tests run with the test-managed transaction DISABLED
 * ( propagation = NOT_SUPPORTED ): {@code @DataJpaTest} would otherwise wrap
 * every test in an uncommitted transaction that worker threads' connections
 * cannot see, breaking the cross-thread concurrency proofs. All seeding and
 * assertions go through {@link #inTx}, which commits real transactions.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public abstract class AgentConnectIntegrationSupport
        extends VoicePostgresIntegrationSupport {

    @Autowired
    protected TenantRepository tenantRepository;
    @Autowired
    protected AgentRepository agentRepository;
    @Autowired
    protected AgentEndpointRepository endpointRepository;
    @Autowired
    protected AgentReservationRepository reservationRepository;
    @Autowired
    protected CallSessionRepository callSessionRepository;
    @Autowired
    protected EntityManager entityManager;
    @Autowired
    protected org.springframework.transaction.PlatformTransactionManager transactionManager;

    protected AgentReservationService reservationService;

    /** Builds the reservation service over the real repositories. */
    protected void initReservationService() {
        reservationService = new AgentReservationService(
                agentRepository, reservationRepository, entityManager);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID seedTenant(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantEntity tenant = new TenantEntity();
        tenant.setName("tenant-" + label + "-" + suffix);
        tenant.setSlug("t-" + label + "-" + suffix);
        tenant.setStatus(com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE);
        return tenantRepository.save(tenant).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID seedAgent(UUID tenantId, String name, AgentAdminStatus adminStatus,
                          AgentAvailability availability, int maxConcurrentCalls) {
        Agent agent = new Agent();
        agent.setTenantId(tenantId);
        agent.setDisplayName(name);
        agent.setAdminStatus(adminStatus);
        agent.setAvailability(availability);
        agent.setMaxConcurrentCalls(maxConcurrentCalls);
        return agentRepository.save(agent).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID seedEndpoint(UUID tenantId, UUID agentId, String dialTarget,
                             com.shivang.obd.voice.call.EndpointType type, boolean enabled) {
        AgentEndpointEntity endpoint = new AgentEndpointEntity();
        endpoint.setAgentId(agentId);
        endpoint.setTenantId(tenantId);
        endpoint.setEndpointType(type);
        endpoint.setDialTarget(dialTarget);
        endpoint.setEnabled(enabled);
        return endpointRepository.save(endpoint).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID seedCallSession(UUID tenantId) {
        CallSession session = new CallSession();
        session.setTenantId(tenantId);
        session.setStatus(com.shivang.obd.voice.call.CallSessionStatus.WAITING_FOR_DTMF);
        session.setDirection(com.shivang.obd.voice.call.CallDirection.OUTBOUND);
        session.setCallType(com.shivang.obd.voice.call.CallType.VOICE_BLAST);
        session.setInitiatedAt(Instant.now());
        session.setProviderCallId("caller-" + UUID.randomUUID());
        return callSessionRepository.save(session).getId();
    }

    /** Raw count of active (non-RELEASED) reservations for an agent, via native SQL. */
    public int activeReservationCount(UUID agentId) {
        return inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM agent_reservations WHERE agent_id = :agentId AND status <> 'RELEASED'")
                    .setParameter("agentId", agentId)
                    .getSingleResult();
            return n.intValue();
        });
    }

    /** Raw count of RELEASED reservations for an agent, via native SQL. */
    public int releasedReservationCount(UUID agentId) {
        return inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM agent_reservations WHERE agent_id = :agentId AND status = 'RELEASED'")
                    .setParameter("agentId", agentId)
                    .getSingleResult();
            return n.intValue();
        });
    }

    /** Runs work in a real committed transaction (test classes are NOT transactional here). */
    public <T> T inTx(java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> work.get());
    }

    /** Clears all test agent data in dependency order (called from @AfterEach). */
    public void cleanupAgentData() {
        inTx(() -> {
            entityManager.createNativeQuery("DELETE FROM agent_reservations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM agent_endpoints").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM agents").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM call_sessions").executeUpdate();
            return null;
        });
    }
}
