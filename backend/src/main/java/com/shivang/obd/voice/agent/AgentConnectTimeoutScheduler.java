package com.shivang.obd.voice.agent;

import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent no-answer timeout (VB-3): agent legs stuck in DIALING/RINGING past
 * the connect timeout are failed as AGENT_NO_ANSWER, the reservation is
 * released (NO_ANSWER) and the agent leg hangs up via the media boundary.
 * <p>
 * Poll-based like every scheduler in this codebase (@Scheduled convention):
 * deterministic, correlated per leg (scans by leg id), idempotent — an
 * already-ANSWERED/BRIDGED/terminal leg is untouched, and the reservation
 * release is the standard idempotent release.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentConnectTimeoutScheduler {

    /** Agent ring window before NO_ANSWER. */
    static final int CONNECT_TIMEOUT_SECONDS = 60;

    private final CallLegRepository callLegRepository;
    private final AgentReservationRepository reservationRepository;
    private final AgentReservationService reservationService;
    private final com.shivang.obd.voice.media.VoiceMediaController mediaController;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void enforceAgentConnectTimeouts() {
        var cutoff = Instant.now().minusSeconds(CONNECT_TIMEOUT_SECONDS);
        List<CallLeg> stalled = callLegRepository.findByLegTypeAndStatusAndInitiatedAtBefore(
                CallLegType.AGENT, CallLegStatus.DIALING, cutoff);
        for (CallLeg leg : stalled) {
            handleTimeout(leg);
        }
        List<CallLeg> ringing = callLegRepository.findByLegTypeAndStatusAndInitiatedAtBefore(
                CallLegType.AGENT, CallLegStatus.RINGING, cutoff);
        for (CallLeg leg : ringing) {
            handleTimeout(leg);
        }
    }

    private void handleTimeout(CallLeg leg) {
        if (leg.getCallSessionId() == null) {
            return;
        }
        log.info("Agent leg {} no answer (callSession={}) — timing out",
                leg.getId(), leg.getCallSessionId());

        leg.setStatus(CallLegStatus.FAILED);
        leg.setEndedAt(Instant.now());
        leg.setFailureCode("AGENT_NO_ANSWER");
        leg.setFailureReason("Agent did not answer within "
                + CONNECT_TIMEOUT_SECONDS + "s");
        callLegRepository.save(leg);

        reservationService.releaseForCallSession(leg.getCallSessionId(), ReleaseReasons.NO_ANSWER);

        try {
            mediaController.terminateCall(leg.getCallSessionId(), leg.getProviderCallId());
        } catch (RuntimeException e) {
            log.warn("Agent leg teardown after no-answer failed for callSession {}: {}",
                    leg.getCallSessionId(), e.getMessage());
        }
    }
}
