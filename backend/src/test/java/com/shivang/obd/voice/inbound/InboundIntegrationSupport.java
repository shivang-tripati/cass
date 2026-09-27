package com.shivang.obd.voice.inbound;

import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidInboundDestination;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.voice.acd.AcdIntegrationSupport;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.call.CallDirection;
import com.shivang.obd.voice.call.CallType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-4D integration support: extends the VB-4C real-PostgreSQL harness
 * (Testcontainers, Flyway chain V1..V39, NOT_SUPPORTED + inTx pattern)
 * with inbound-DID seeding and the inbound call service over real
 * repositories — including the real {@code AgentReservationService} and
 * a failing {@code AgentLegDialer} stub whose behavior tests control
 * (the real FreeSWITCH dialer is a @ConditionalOnProperty bean outside
 * the @DataJpaTest slice).
 */
public abstract class InboundIntegrationSupport extends AcdIntegrationSupport {

    @Autowired
    protected DidRepository didRepository;
    @Autowired
    protected CallSessionRepository sessionRepository;
    @Autowired
    protected CallLegRepository legRepository;

    protected InboundCallService inboundCallService;
    protected InboundAcdRetryScheduler retryScheduler;

    /** Dialer stub: throws by default; tests can flip to succeed. */
    protected StubAgentLegDialer dialer;

    protected void initInboundServices() {
        initAcdServices();
        dialer = new StubAgentLegDialer();
        inboundCallService = new InboundCallService(
                entityManager, didRepository, queueRepository, waitingCallRepository,
                agentRepository, endpointRepository, reservationService,
                acdService, sessionRepository, legRepository, dialer,
                new FailingMediaController(), java.util.Optional.empty());
        retryScheduler = new InboundAcdRetryScheduler(
                waitingCallRepository, acdService, inboundCallService);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public UUID seedInboundDid(UUID tenantId, String e164,
                               DidInboundDestination destination, UUID queueId, UUID agentId) {
        DidEntity did = new DidEntity();
        did.setTenantId(tenantId);
        did.setResellerId(null);
        did.setE164Number(e164);
        did.setCountryCode("1");
        did.setNumberType(com.shivang.obd.did.NumberType.LANDLINE);
        did.setProvider("test");
        did.setInboundDestination(destination);
        did.setInboundQueueId(queueId);
        did.setInboundAgentId(agentId);
        return didRepository.save(did).getId();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public UUID seedInboundSession(UUID tenantId, String channelUuid, UUID didId) {
        CallSession session = new CallSession();
        session.setTenantId(tenantId);
        session.setDirection(CallDirection.INBOUND);
        session.setCallType(CallType.CONTACT_CENTER_INBOUND);
        session.setStatus(CallSessionStatus.ANSWERED);
        session.setDidId(didId);
        session.setProviderCallId(channelUuid);
        session.setInitiatedAt(Instant.now());
        session = sessionRepository.save(session);
        CallLeg leg = new CallLeg();
        leg.setCallSessionId(session.getId());
        leg.setLegType(CallLegType.CUSTOMER);
        leg.setDirection(CallDirection.INBOUND);
        leg.setStatus(com.shivang.obd.voice.call.CallLegStatus.ANSWERED);
        leg.setProviderCallId(channelUuid);
        legRepository.save(leg);
        return session.getId();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public UUID seedMembership(UUID tenantId, UUID queueId, UUID agentId) {
        var m = new com.shivang.obd.voice.queue.QueueMembership();
        m.setTenantId(tenantId);
        m.setQueueId(queueId);
        m.setAgentId(agentId);
        m.setStatus(com.shivang.obd.voice.queue.QueueMemberStatus.ACTIVE);
        return membershipRepository.save(m).getId();
    }

    /** Live CUSTOMER leg of a session. */
    public CallLeg callerLeg(UUID sessionId) {
        return inTx(() -> legRepository
                .findByCallSessionIdAndLegTypeAndDeletedAtIsNull(sessionId, CallLegType.CUSTOMER)
                .stream().findFirst().orElse(null));
    }

    /** Live AGENT leg of a session, or null. */
    public CallLeg agentLeg(UUID sessionId) {
        return inTx(() -> legRepository
                .findByCallSessionIdAndLegTypeAndDeletedAtIsNull(sessionId, CallLegType.AGENT)
                .stream().findFirst().orElse(null));
    }

    /** Clears all test inbound/queue/agent/call data in FK-safe order. */
    @Override
    public void cleanupAgentData() {
        super.cleanupAgentData();
        // DIDs must be cleared AFTER call_sessions (sessions.did_id FK);
        // the inherited chain has removed sessions/legs/queues by now.
        inTx(() -> {
            entityManager.createNativeQuery("DELETE FROM dids").executeUpdate();
            return null;
        });
    }

    /** Dialer stub controlled by tests (originate succeeds → fixed UUID). */
    public static class StubAgentLegDialer
            implements com.shivang.obd.voice.agent.AgentLegDialer {
        public boolean succeed = false;

        @Override
        public String originateAgentLeg(String callerId, String dialTarget,
                                        String gatewayName, String profile) {
            if (!succeed) {
                throw new com.shivang.obd.telephony.EslException("stub originate failure");
            }
            return "agent-uuid-" + UUID.randomUUID();
        }
    }

    /** Media controller stub: no-ops for terminate/bridge (other methods unsupported). */
    public static class FailingMediaController
            implements com.shivang.obd.voice.media.VoiceMediaController {
        @Override
        public void terminateCall(UUID callSessionId, String providerCallId) {
            // no-op: teardown is asserted via leg state
        }

        @Override
        public void bridge(UUID callSessionId, UUID legAId, UUID legBId) {
            // no-op
        }

        @Override
        public void playAudio(UUID callSessionId, UUID legId, String audioUri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stopPlayback(UUID callSessionId, UUID legId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String collectDtmf(UUID callSessionId, UUID legId, int maxDigits,
                                  String terminator, int timeoutSecs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void startRecording(UUID callSessionId, UUID legId, String format) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stopRecording(UUID callSessionId, UUID legId) {
            throw new UnsupportedOperationException();
        }
    }
}
