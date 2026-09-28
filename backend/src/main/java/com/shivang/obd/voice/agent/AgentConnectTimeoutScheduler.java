package com.shivang.obd.voice.agent;

import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent no-answer timeout (VB-3; VB-7A per-leg ring budget): agent legs stuck
 * in DIALING/RINGING past the connect timeout are failed as AGENT_NO_ANSWER,
 * the reservation is released (NO_ANSWER) and the agent leg hangs up via the
 * media boundary.
 * <p>
 * Poll-based like every scheduler in this codebase (@Scheduled convention):
 * deterministic, correlated per leg (scans by leg id), idempotent — an
 * already-ANSWERED/BRIDGED/terminal leg is untouched, and the reservation
 * release is the standard idempotent release.
 *
 * <h2>VB-7A — one authority, a per-leg budget</h2>
 *
 * <p>This scheduler remains the <b>only</b> enforcer of the agent ring window.
 * What changed is where the window comes from: it used to be the single constant
 * below, and is now either that constant or the ring budget frozen in the
 * execution's snapshot, resolved per leg through
 * {@link AgentRingBudgetResolver}.
 *
 * <p>Because budgets now differ per campaign, one global cutoff can no longer be
 * correct — a leg with a 240s budget must not be failed at 60s, and a leg with a
 * 10s budget must not wait for 240s. The sweep therefore over-fetches legs
 * initiated before {@code now - MAX_RING_SECONDS} (safe: no budget exceeds the
 * maximum, so nothing due is missed) and then applies each leg's own deadline.
 * The constant is retained as the <em>default</em>, so every leg with no
 * configured budget — including a DTMF/IVR campaign whose terminal action is
 * {@code CONNECT_BY_AGENT} — is enforced exactly as it was before VB-7A.
 *
 * <p>{@link AgentRingWindow} owns the bounds and the default, so the campaign
 * configuration validator and this scheduler can never disagree about what is
 * a legal window.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentConnectTimeoutScheduler {

    /**
     * Default agent ring window, in seconds, for a leg with no configured
     * budget. The pre-VB-7A constant, unchanged; the single authority for the
     * fallback lives in {@link AgentRingWindow}.
     */
    static final int CONNECT_TIMEOUT_SECONDS = AgentRingWindow.DEFAULT_RING_SECONDS;

    private final CallLegRepository callLegRepository;
    private final AgentReservationRepository reservationRepository;
    private final AgentReservationService reservationService;
    private final com.shivang.obd.voice.media.VoiceMediaController mediaController;
    /**
     * VB-7A: optional, so an agent-only deployment without the campaign module
     * still enforces the platform default.
     */
    private final org.springframework.beans.factory.ObjectProvider<AgentRingBudgetResolver>
            ringBudgetResolver;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void enforceAgentConnectTimeouts() {
        // Over-inclusive on purpose: every budget is at most
        // AgentRingWindow.MAX_RING_SECONDS, so a leg whose own deadline has
        // passed is always inside this window, while a leg with a longer budget
        // is fetched early and simply not yet due.
        var cutoff = Instant.now().minusSeconds(AgentRingWindow.MAX_RING_SECONDS);
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
        int ringSeconds = ringSecondsFor(leg.getCallSessionId());
        if (!AgentRingWindow.isExpired(leg.getInitiatedAt(), ringSeconds)) {
            log.debug("Agent leg {} not yet past its {}s ring window — waiting",
                    leg.getId(), ringSeconds);
            return;
        }
        log.info("Agent leg {} no answer (callSession={}, ring={}s) — timing out",
                leg.getId(), leg.getCallSessionId(), ringSeconds);

        leg.setStatus(CallLegStatus.FAILED);
        leg.setEndedAt(Instant.now());
        leg.setFailureCode("AGENT_NO_ANSWER");
        leg.setFailureReason("Agent did not answer within " + ringSeconds + "s");
        callLegRepository.save(leg);

        reservationService.releaseForCallSession(leg.getCallSessionId(), ReleaseReasons.NO_ANSWER);

        try {
            mediaController.terminateCall(leg.getCallSessionId(), leg.getProviderCallId());
        } catch (RuntimeException e) {
            log.warn("Agent leg teardown after no-answer failed for callSession {}: {}",
                    leg.getCallSessionId(), e.getMessage());
        }
    }

    /**
     * The ring window for one leg. Falls back to the platform default whenever
     * no budget applies or the resolver is unavailable — a missing or
     * unresolvable budget must never fail a call in progress.
     */
    private int ringSecondsFor(UUID callSessionId) {
        try {
            AgentRingBudgetResolver resolver =
                    ringBudgetResolver == null ? null : ringBudgetResolver.getIfAvailable();
            if (resolver == null) {
                return CONNECT_TIMEOUT_SECONDS;
            }
            return AgentRingWindow.effectiveSeconds(
                    resolver.ringSecondsFor(callSessionId, CONNECT_TIMEOUT_SECONDS));
        } catch (RuntimeException e) {
            log.warn("Ring budget resolution failed for callSession {}: {} — using the "
                    + "platform default of {}s", callSessionId, e.getMessage(),
                    CONNECT_TIMEOUT_SECONDS);
            return CONNECT_TIMEOUT_SECONDS;
        }
    }
}
