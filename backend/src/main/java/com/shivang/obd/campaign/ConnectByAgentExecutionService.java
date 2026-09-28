package com.shivang.obd.campaign;

import com.shivang.obd.voice.agent.AgentConnectRequest;
import com.shivang.obd.voice.agent.AgentConnectTrigger;
import com.shivang.obd.voice.agent.AgentEligibility;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.media.PlaybackTrigger;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * CONNECT_BY_AGENT campaign execution (VB-7A) — the answer-event entry point
 * for a campaign whose <em>type</em> is
 * {@link CampaignType#CONNECT_BY_AGENT}.
 *
 * <h2>Why this class had to exist</h2>
 *
 * <p>Before VB-7A a CONNECT_BY_AGENT campaign had no runtime at all. Every
 * {@link PlaybackTrigger} guards on campaign type — {@code PlayfileExecutionService}
 * returns unless the type is {@code PLAYFILE}, {@code DtmfExecutionService}
 * unless it is {@code DTMF} — and the outbound dialer gates on nothing. So such
 * a campaign was dialed, the callee answered, every trigger declined, and the
 * call sat answered until the VB-6E maximum-duration deadline reaped it. The only
 * working agent connection was the <em>input-driven</em> one: a DTMF or IVR
 * campaign whose terminal action happens to be {@code CONNECT_BY_AGENT}. Two
 * different things that shared a name; only the second one ran.
 *
 * <p>This class is what makes the first one run, and it is the reason the
 * distinction is now explicit rather than accidental: a <b>campaign type</b> of
 * CONNECT_BY_AGENT enters here on answer, while a <b>terminal action</b> of
 * CONNECT_BY_AGENT on a DTMF/IVR campaign continues to be dispatched by
 * {@code DtmfExecutionService} / {@code IvrExecutionService}, unchanged.
 *
 * <h2>What this class does and does not own</h2>
 *
 * <p>It owns exactly three decisions: that this call is a CONNECT_BY_AGENT
 * campaign call, that the call is still on the line and has not already been
 * connected, and which frozen configuration the request carries. It then hands
 * over to the existing {@link AgentConnectTrigger} boundary, which owns
 * selection, reservation, the agent leg, the originate and — through the shared
 * {@code AgentConnectEvents} callbacks — answer, bridge and hangup.
 *
 * <p>It plays no media. It is a {@link PlaybackTrigger} for the same reason
 * {@code DtmfExecutionService} is: that interface is the established
 * CHANNEL_ANSWER seam into the campaign execution layer, and its own javadoc
 * states that implementations act only for the campaign types they own so that
 * several trigger beans coexist without dispatch ambiguity.
 *
 * <h2>Execution semantics of a CONNECT_BY_AGENT campaign</h2>
 *
 * <pre>
 * dial contact → call answered → read the FROZEN snapshot → ask the configured
 * queue's ACD authority for an eligible agent → reserve atomically → originate
 * the agent leg → ring for the configured window → answer → bridge
 * </pre>
 *
 * <p>No AI agent, no predictive or skills routing, no dial-strategy change. The
 * agent is an existing {@code Agent} row, a member of the configured queue, and
 * is selected by the existing deterministic order.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConnectByAgentExecutionService implements PlaybackTrigger {

    /** Failure code when the campaign's frozen configuration cannot be used. */
    public static final String AGENT_CONNECT_CONFIG_INVALID_CODE = "AGENT_CONFIG_INVALID";

    private final CallSessionRepository callSessionRepository;
    private final CallAttemptRepository callAttemptRepository;
    private final CampaignExecutionRepository executionRepository;
    private final CampaignRuntimeConfigResolver runtimeConfigResolver;
    private final VoiceMediaController mediaController;
    /**
     * Optional and lazily injected, matching {@code DtmfExecutionService}: a
     * deployment without the agent layer still starts, and a
     * CONNECT_BY_AGENT campaign in such a deployment fails deterministically
     * instead of crashing the event thread.
     */
    private final org.springframework.beans.factory.ObjectProvider<AgentConnectTrigger>
            agentConnectTrigger;

    @Override
    public void onAnswered(UUID callSessionId, UUID attemptId) {
        if (callSessionId == null || attemptId == null) {
            return;
        }
        CallSession session = callSessionRepository.findByIdAndDeletedAtIsNull(callSessionId)
                .orElse(null);
        if (session == null) {
            log.debug("CONNECT_BY_AGENT trigger: call session {} not found", callSessionId);
            return;
        }

        // Idempotency: only the first ANSWERED transition connects. A duplicate
        // CHANNEL_ANSWER, or a call already being bridged, is a no-op.
        if (session.getStatus() != CallSessionStatus.ANSWERED) {
            log.debug("CONNECT_BY_AGENT trigger: session {} in state {} — ignoring",
                    callSessionId, session.getStatus());
            return;
        }

        CallAttempt attempt = loadAttempt(attemptId);
        if (attempt == null) {
            return;
        }

        // VB-6A: the configuration is the execution's immutable snapshot, never
        // the live campaign.
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig config = resolveExecutionConfig(attempt);
        if (config == null) {
            recordConfigFailure(session,
                    "The execution configuration for this call is missing");
            return;
        }

        // Only CONNECT_BY_AGENT campaigns connect here. A PLAYFILE or DTMF call
        // is owned by its own trigger, which has already run.
        if (config.campaignType() != CampaignType.CONNECT_BY_AGENT) {
            log.debug("Campaign {} is {} — not CONNECT_BY_AGENT, no agent connect",
                    config.campaignId(), config.campaignType());
            return;
        }

        // A CONNECT_BY_AGENT campaign reaches this point only with a readable
        // snapshot; an unreadable one means the execution was corrupted, and a
        // silent no-op would be the same dead call this class exists to prevent.
        if (config.asConnectByAgent().isEmpty()) {
            recordConfigFailure(session,
                    "CONNECT_BY_AGENT campaign has no readable agent configuration in its "
                            + "execution snapshot");
            return;
        }

        AgentConnectTrigger trigger = agentConnectTrigger == null
                ? null : agentConnectTrigger.getIfAvailable();
        if (trigger == null) {
            log.warn("CONNECT_BY_AGENT requested for session {} but no agent connect trigger is "
                    + "configured", callSessionId);
            recordConfigFailure(session,
                    "Agent connection is not supported in this deployment");
            return;
        }

        AgentConnectRequest request = config.agentConnectRequest();
        try {
            AgentEligibility outcome = trigger.connectByAgent(
                    callSessionId, attemptId, request);
            log.info("CONNECT_BY_AGENT campaign executed (callSession={}, queue={}, ring={}s, "
                            + "selected={}, reason={})",
                    callSessionId, request.queueId(), request.effectiveRingSeconds(),
                    outcome.isSelected(), outcome.reasonCode());
        } catch (RuntimeException e) {
            // The connect service finalizes the call on its own failures; this
            // boundary must never break the event thread. If the failure escaped
            // that far the call is still on the line and nothing else will
            // finalize it, so fail it here rather than leaving it answered.
            log.warn("CONNECT_BY_AGENT dispatch failed for session {}: {}",
                    callSessionId, e.getMessage());
            recordConfigFailure(session,
                    "Agent connection failed: " + e.getMessage());
        }
    }

    /**
     * {@code onPlaybackCompleted} is a no-op: a CONNECT_BY_AGENT call plays
     * nothing, so there is no playback completion to act on. The bridge
     * confirmation is what advances this call type, and it is owned by the
     * shared agent-connect event path.
     */
    @Override
    public void onPlaybackCompleted(UUID callSessionId, UUID attemptId) {
        // intentionally empty
    }

    private CallAttempt loadAttempt(UUID attemptId) {
        CallAttempt attempt = callAttemptRepository.findByIdAndDeletedAtIsNull(attemptId)
                .orElse(null);
        if (attempt == null) {
            log.debug("CONNECT_BY_AGENT trigger: attempt {} not found", attemptId);
        }
        return attempt;
    }

    private CampaignRuntimeConfigResolver.CampaignRuntimeConfig resolveExecutionConfig(
            CallAttempt attempt) {
        CampaignExecution execution = executionRepository
                .findByIdAndDeletedAtIsNull(attempt.getExecutionId())
                .orElse(null);
        if (execution == null) {
            return null;
        }
        return runtimeConfigResolver.resolve(execution);
    }

    /**
     * Fails the call deterministically. Same shape as the DTMF and PLAYFILE
     * config-failure paths: record the reason, then ask the media boundary to
     * tear the channel down so the caller is not left listening to silence.
     */
    private void recordConfigFailure(CallSession session, String reason) {
        if (session.getStatus() != CallSessionStatus.COMPLETED
                && session.getStatus() != CallSessionStatus.FAILED
                && session.getStatus() != CallSessionStatus.CANCELLED) {
            session.setFailureCode(AGENT_CONNECT_CONFIG_INVALID_CODE);
            session.setFailureReason(reason);
            callSessionRepository.save(session);
        }
        try {
            mediaController.terminateCall(session.getId(), session.getProviderCallId());
        } catch (RuntimeException e) {
            log.warn("Teardown after CONNECT_BY_AGENT configuration failure failed for session "
                    + "{}: {}", session.getId(), e.getMessage());
        }
    }
}
