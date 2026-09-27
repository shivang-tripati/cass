package com.shivang.obd.campaign;

import com.shivang.obd.audio.MediaUriResolver;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.dtmf.DtmfActions;
import com.shivang.obd.voice.ivr.IvrExecutionSnapshot;
import com.shivang.obd.voice.ivr.IvrNodeSnapshot;
import com.shivang.obd.voice.ivr.IvrNodeType;
import com.shivang.obd.voice.ivr.IvrStep;
import com.shivang.obd.voice.ivr.IvrStepRepository;
import com.shivang.obd.voice.ivr.IvrStepResultType;
import com.shivang.obd.voice.ivr.IvrTerminalAction;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The IVR runtime: turns the existing DTMF runtime into an engine for the
 * frozen IVR snapshot (VB-6F).
 *
 * <h2>It is an engine over the snapshot, not a second runtime</h2>
 *
 * <p>Every entry point here is invoked <em>from</em>
 * {@code DtmfExecutionService}, which remains the single DTMF event consumer
 * reached by the single existing {@code DtmfCollectorTrigger} boundary. There is
 * no second CHANNEL_DTMF consumer, no second scheduler and no new telephony:
 * digits still arrive through {@code EslEventService} and per-node deadlines are
 * still scanned by the existing 1-second {@code DtmfTimeoutScheduler}.
 *
 * <h2>What is read at runtime</h2>
 *
 * <p>Only {@link IvrExecutionSnapshot} and {@link IvrStep}. Never
 * {@code IvrNode} or {@code IvrTransition}. That is what makes a live IVR edit
 * unable to move a caller who is already in the old flow — the edit produces a
 * new tree, and only an execution created afterwards captures it.
 *
 * <h2>Invalid input, no input, and retries</h2>
 *
 * <p>Three distinct outcomes, deliberately not conflated:
 * <ul>
 *   <li><b>valid</b> — a transition exists; the caller advances;</li>
 *   <li><b>invalid input</b> — a digit with no transition, which cannot become
 *       valid by collecting more (the same reasoning the single-level collector
 *       already used);</li>
 *   <li><b>no input</b> — the wait window elapsed with no digit at all.</li>
 * </ul>
 * <p>Invalid input and no input keep <b>separate prompt slots and separate retry
 * budgets</b>, because they are different things that happened to a caller.
 * Counts mean <b>additional attempts after the first</b>, so {@code 0} gives one
 * attempt and {@code 2} gives three.
 *
 * <h2>Retries never touch campaign retry machinery</h2>
 *
 * <p>An IVR input retry happens inside one call: same session, same
 * {@code CallAttempt}, same provider dial. It consumes no VB-6C accepted-dial
 * quota, no VB-6D.3 daily attempt, and never reaches
 * {@code RetryPolicyService}. Exhausting a node's retries resolves the node's
 * terminal action; it does not fail the attempt. Asserted by test, because mixing
 * these two retry domains is the single most dangerous confusion this phase
 * could introduce.
 *
 * <h2>Maximum call duration</h2>
 *
 * <p>This service never writes {@code call_sessions.deadline_at}. VB-6E's single
 * authoritative deadline stands, and a node wait can neither extend nor reset
 * it, so entering another node does not give a caller more total time.
 */
@Service
@Lazy
@Slf4j
public class IvrExecutionService {

    /** The IVR configuration is unusable. Permanent: retrying cannot succeed. */
    public static final String IVR_CONFIG_INVALID_CODE = "IVR_CONFIG_INVALID";

    /**
     * The IVR runtime paired with the frozen configuration a call is running.
     * <p>
     * Carried as one value so a caller cannot pair a runtime with a different
     * call's snapshot by accident, and so the dispatch from
     * {@code DtmfExecutionService} reads as a single decision.
     *
     * @param config  the campaign's IVR configuration, holding the frozen snapshot
     * @param runtime the runtime to drive
     */
    public record IvrCall(com.shivang.obd.campaign.config.IvrCampaignConfig config,
                          IvrExecutionService runtime) {
    }

    private final CallSessionRepository callSessionRepository;
    private final CallLegRepository callLegRepository;
    private final IvrStepRepository stepRepository;
    private final CampaignIvrPromptGovernance promptGovernance;
    private final com.shivang.obd.audio.AudioAssetRepository audioAssetRepository;
    private final MediaUriResolver mediaUriResolver;
    private final VoiceMediaController mediaController;

    /**
     * VB-3 reuse: the existing agent-connect boundary. Optional because an
     * agent-less deployment still runs IVR trees whose terminal action is
     * TERMINATE.
     */
    private final ObjectProvider<com.shivang.obd.voice.agent.AgentConnectTrigger> agentConnectTrigger;

    public IvrExecutionService(
            CallSessionRepository callSessionRepository,
            CallLegRepository callLegRepository,
            IvrStepRepository stepRepository,
            CampaignIvrPromptGovernance promptGovernance,
            com.shivang.obd.audio.AudioAssetRepository audioAssetRepository,
            MediaUriResolver mediaUriResolver,
            @Lazy VoiceMediaController mediaController,
            ObjectProvider<com.shivang.obd.voice.agent.AgentConnectTrigger> agentConnectTrigger) {
        this.callSessionRepository = callSessionRepository;
        this.callLegRepository = callLegRepository;
        this.stepRepository = stepRepository;
        this.promptGovernance = promptGovernance;
        this.audioAssetRepository = audioAssetRepository;
        this.mediaUriResolver = mediaUriResolver;
        this.mediaController = mediaController;
        this.agentConnectTrigger = agentConnectTrigger;
    }

    // =====================================================================
    // Answer: begin at the root
    // =====================================================================

    /**
     * CHANNEL_ANSWER for an IVR campaign: opens the first step at the root and
     * plays the root prompt.
     * <p>
     * Idempotent: an attempt that already has a step is left alone, so a
     * duplicate answer event cannot restart the flow.
     */
    @Transactional
    public void onAnswered(UUID callSessionId, UUID callAttemptId,
                           IvrExecutionSnapshot snapshot) {
        Optional<CallSession> sessionOpt = callSessionRepository.findById(callSessionId);
        if (sessionOpt.isEmpty()) {
            return;
        }
        CallSession session = sessionOpt.get();
        if (session.getStatus() != CallSessionStatus.ANSWERED) {
            return;
        }
        if (stepRepository
                .findFirstByCallSessionIdAndResultAndDeletedAtIsNullOrderByCreatedAtDesc(
                        callSessionId, IvrStepResultType.WAITING_INPUT).isPresent()) {
            log.debug("IVR step already open for session {} - ignoring duplicate answer", callSessionId);
            return;
        }

        IvrNodeSnapshot root = snapshot.root();
        if (root.terminal()) {
            // A tree whose root is TERMINAL is legal but odd; honour it rather
            // than inventing a node to wait on.
            recordTerminal(callSessionId, callAttemptId, root, "IVR root is a terminal node");
            return;
        }

        openStep(session, callAttemptId, snapshot, root);
        playPrompt(session, root.promptAudioAssetId(), "root");
    }

    // =====================================================================
    // PLAYBACK_STOP: start waiting for input
    // =====================================================================

    /**
     * PLAYBACK_STOP for an IVR campaign: moves the session to
     * {@code WAITING_FOR_DTMF}, which already means "waiting for caller input" and
     * is reused unchanged.
     * <p>
     * The step was opened at answer time, so this only arms the wait. Digits
     * arriving during playback are ignored because no step is
     * {@code WAITING_INPUT} until the prompt has finished.
     */
    @Transactional
    public void onPlaybackCompleted(UUID callSessionId, UUID callAttemptId) {
        Optional<CallSession> sessionOpt = callSessionRepository.findById(callSessionId);
        if (sessionOpt.isEmpty()) {
            return;
        }
        CallSession session = sessionOpt.get();
        if (session.getStatus() != CallSessionStatus.PLAYBACK_COMPLETED) {
            return;
        }
        session.setStatus(CallSessionStatus.WAITING_FOR_DTMF);
        callSessionRepository.save(session);
        log.info("IVR now waiting for input (callSession={}, attempt={})",
                callSessionId, callAttemptId);
    }

    // =====================================================================
    // CHANNEL_DTMF
    // =====================================================================

    /**
     * A DTMF digit was reported.
     *
     * <p>Three outcomes, decided purely from the frozen snapshot:
     * a transition for the digit advances the caller; no transition is an invalid
     * input; and a digit arriving when the current step is no longer waiting —
     * because it already advanced, was already terminal, or the call ended — is
     * ignored. That last case is what makes duplicate and late DTMF safe, and it
     * is enforced by the same atomic claim the single-level runtime uses.
     */
    @Transactional
    public void onDtmfDigit(UUID callSessionId, UUID callAttemptId,
                            IvrExecutionSnapshot snapshot, String digit) {
        if (digit == null || digit.length() != 1) {
            return;
        }
        Optional<IvrStep> stepOpt = stepRepository
                .findFirstByCallSessionIdAndResultAndDeletedAtIsNullOrderByCreatedAtDesc(
                        callSessionId, IvrStepResultType.WAITING_INPUT);
        if (stepOpt.isEmpty()) {
            // No open step: during playback, after advancing, after a terminal
            // action, or after hangup. Deterministically ignored.
            log.debug("No open IVR step for session {} - digit ignored", callSessionId);
            return;
        }
        IvrStep step = stepOpt.get();

        if (Instant.now().isAfter(step.getExpiresAt())) {
            // The timeout poller owns finalization; a late digit must not win it.
            log.debug("IVR step {} expired - digit ignored", step.getId());
            return;
        }

        Optional<CallSession> sessionOpt = callSessionRepository.findById(callSessionId);
        if (sessionOpt.isEmpty()) {
            return;
        }
        CallSession session = sessionOpt.get();

        Optional<String> targetKey = snapshot.resolve(digit.charAt(0), step.getNodeKey());
        if (targetKey.isEmpty()) {
            handleInvalidInput(session, callAttemptId, snapshot, step, digit.charAt(0));
            return;
        }

        Optional<IvrNodeSnapshot> target = snapshot.node(targetKey.get());
        if (target.isEmpty()) {
            // The snapshot disagrees with itself. Treat as an invalid input
            // rather than trusting an unresolvable target.
            log.warn("IVR snapshot has no node '{}' for a declared transition", targetKey.get());
            handleInvalidInput(session, callAttemptId, snapshot, step, digit.charAt(0));
            return;
        }

        // Win the claim before acting, so a racing duplicate digit or timeout
        // cannot both advance the caller.
        if (!claim(step, IvrStepResultType.ADVANCED, "Transition on input '" + digit + "'")) {
            log.debug("IVR step {} already resolved - duplicate digit ignored", step.getId());
            return;
        }

        if (target.get().terminal()) {
            recordTerminal(callSessionId, callAttemptId, target.get(),
                    "IVR reached terminal node " + target.get().nodeKey());
            return;
        }

        openStep(session, callAttemptId, snapshot, target.get());
        playPrompt(session, target.get().promptAudioAssetId(), target.get().nodeKey());
    }

    // =====================================================================
    // Per-node timeout
    // =====================================================================

    /**
     * The current node's wait window elapsed.
     * <p>
     * Claimed atomically, so a digit arriving in the same instant wins exactly
     * once and the loser does nothing.
     */
    @Transactional
    public void onStepTimeout(UUID callSessionId) {
        Optional<IvrStep> stepOpt = stepRepository
                .findFirstByCallSessionIdAndResultAndDeletedAtIsNullOrderByCreatedAtDesc(
                        callSessionId, IvrStepResultType.WAITING_INPUT);
        if (stepOpt.isEmpty()) {
            return;
        }
        IvrStep step = stepOpt.get();
        Optional<CallSession> sessionOpt = callSessionRepository.findById(callSessionId);
        if (sessionOpt.isEmpty()) {
            return;
        }
        handleNoInput(sessionOpt.get(), step.getCallAttemptId(), step);
    }

    /**
     * Marks any open step ABANDONED when the call ends.
     * <p>
     * Mirrors the single-level runtime's {@code abandonIfCollecting}, so a
     * caller who hangs up mid-IVR leaves an accurate audit trail rather than an
     * open step.
     */
    @Transactional
    public void abandonIfWaiting(UUID callSessionId) {
        stepRepository
                .findFirstByCallSessionIdAndResultAndDeletedAtIsNullOrderByCreatedAtDesc(
                        callSessionId, IvrStepResultType.WAITING_INPUT)
                .ifPresent(step -> claim(step, IvrStepResultType.ABANDONED,
                        "Call ended before the IVR reached a result"));
    }

    // =====================================================================
    // Invalid input / no input
    // =====================================================================

    /**
     * A digit with no transition. The caller is re-asked, in the same call, until
     * the node's budget is spent.
     */
    private void handleInvalidInput(CallSession session, UUID callAttemptId,
                                    IvrExecutionSnapshot snapshot, IvrStep step, char digit) {
        IvrNodeSnapshot node = snapshot.node(step.getNodeKey()).orElse(null);
        if (node == null) {
            recordConfigFailure(session,
                    "IVR snapshot lost the node the caller is on: " + step.getNodeKey());
            return;
        }

        int used = step.getInvalidAttempts() == null ? 0 : step.getInvalidAttempts();
        int budget = node.invalidInputRetries(); // ADDITIONAL attempts after the first
        boolean exhausted = used >= budget;

        if (!claim(step,
                exhausted ? IvrStepResultType.TERMINAL_REACHED : IvrStepResultType.INVALID_RETRY,
                "Unrecognised input '" + digit + "' at node '" + node.nodeKey() + "'")) {
            return; // a duplicate digit already handled this
        }

        log.info("IVR invalid input (callSession={}, node={}, digit={}, used={}/{})",
                session.getId(), node.nodeKey(), digit, used + 1, budget + 1);

        if (exhausted) {
            resolveAfterRetriesExhausted(session, callAttemptId, node, step,
                    "no valid input after " + (budget + 1) + " attempt(s) at node '"
                            + node.nodeKey() + "'");
            return;
        }

        // Re-open the SAME node with a fresh window and the incremented counter.
        IvrStep retry = newStep(session, callAttemptId, snapshot, node);
        retry.setInvalidAttempts(used + 1);
        retry.setNoInputAttempts(step.getNoInputAttempts());
        stepRepository.save(retry);
        session.setStatus(CallSessionStatus.WAITING_FOR_DTMF);
        callSessionRepository.save(session);
        playPrompt(session, node.invalidPromptAudioAssetId(), node.nodeKey() + " (invalid)");
    }

    /**
     * The wait window elapsed with no digit. Distinct from an invalid input and
     * with its own budget, because "pressed the wrong key" and "pressed nothing"
     * are different things to a caller.
     * <p>
     * The node's budgets and prompt come from the <em>frozen step row</em>, not
     * from the snapshot: the timeout path is dispatched with only a session id,
     * and reading live IVR rows here would defeat the snapshot guarantee. The
     * no-input prompt is deliberately not played, because the step does not
     * carry a prompt reference — see the note in {@link #onStepTimeout}.
     */
    private void handleNoInput(CallSession session, UUID callAttemptId, IvrStep step) {
        int used = step.getNoInputAttempts() == null ? 0 : step.getNoInputAttempts();
        int budget = step.getNoInputRetries() == null ? 0 : step.getNoInputRetries();
        boolean exhausted = used >= budget;

        if (!claim(step,
                exhausted ? IvrStepResultType.TERMINAL_REACHED : IvrStepResultType.NO_INPUT_RETRY,
                "No input within " + step.getInputWaitSeconds() + "s at node '"
                        + step.getNodeKey() + "'")) {
            return;
        }

        log.info("IVR no input (callSession={}, node={}, used={}/{})",
                session.getId(), step.getNodeKey(), used + 1, budget + 1);

        if (exhausted) {
            hangUp(session, "IVR no input after " + (budget + 1) + " attempt(s) at node '"
                    + step.getNodeKey() + "'");
            return;
        }

        // Re-open the SAME node with a fresh window and the incremented counter.
        // No prompt is played: the step row carries budgets, not prompt ids, and
        // resolving the snapshot here would put a live-tree read on the timeout
        // path. The caller is simply re-asked.
        IvrStep retry = new RetryStep(step, used + 1).build();
        stepRepository.save(retry);
        session.setStatus(CallSessionStatus.WAITING_FOR_DTMF);
        callSessionRepository.save(session);
    }

    /**
     * What happens when a node's retries are spent.
     * <p>
     * The call's IVR is over. It does <b>not</b> fail the {@code CallAttempt}:
     * an exhausted input budget is a conversation outcome, not a dispatch
     * failure, and failing it here would push a caller-input outcome through the
     * campaign retry policy. The call simply ends, which is what a tenant means
     * by "that is all the times I will ask".
     */
    private void resolveAfterRetriesExhausted(CallSession session, UUID callAttemptId,
                                              IvrNodeSnapshot node, IvrStep step,
                                              String reason) {
        log.info("IVR node '{}' exhausted its input budget ({}) - ending the call",
                node.nodeKey(), reason);
        hangUp(session, "IVR " + reason);
    }

    private void recordTerminal(UUID callSessionId, UUID callAttemptId, IvrNodeSnapshot node,
                                String reason) {
        Optional<CallSession> sessionOpt = callSessionRepository.findById(callSessionId);
        if (sessionOpt.isEmpty()) {
            return;
        }
        CallSession session = sessionOpt.get();

        if (node.terminalAction() == IvrTerminalAction.CONNECT_BY_AGENT) {
            dispatchConnectByAgent(callSessionId, callAttemptId, reason);
            return;
        }
        log.info("IVR reached terminal node '{}' ({})", node.nodeKey(), reason);
        hangUp(session, reason);
    }

    // =====================================================================
    // Step lifecycle helpers
    // =====================================================================

    private void openStep(CallSession session, UUID callAttemptId,
                          IvrExecutionSnapshot snapshot, IvrNodeSnapshot node) {
        stepRepository.save(newStep(session, callAttemptId, snapshot, node));
        session.setStatus(CallSessionStatus.PLAYING);
        callSessionRepository.save(session);
    }

    private IvrStep newStep(CallSession session, UUID callAttemptId,
                            IvrExecutionSnapshot snapshot, IvrNodeSnapshot node) {
        IvrStep step = new IvrStep();
        step.setTenantId(session.getTenantId());
        step.setCallSessionId(session.getId());
        step.setCallAttemptId(callAttemptId);
        step.setTreeId(snapshot == null ? null : snapshot.treeId());
        step.setNodeKey(node.nodeKey());
        step.setNodeType(node.nodeType());
        // Frozen copies: a live IVR edit cannot move a deadline already in flight
        // or change a budget the caller is already spending.
        step.setInputWaitSeconds(node.inputWaitSeconds());
        step.setInvalidInputRetries(node.invalidInputRetries());
        step.setNoInputRetries(node.noInputRetries());
        step.setInvalidAttempts(0);
        step.setNoInputAttempts(0);
        step.setResult(IvrStepResultType.WAITING_INPUT);
        step.setExpiresAt(Instant.now().plusSeconds(node.inputWaitSeconds()));
        return step;
    }

    /**
     * A re-ask of the same node after no input, built from the previous step
     * rather than from a snapshot.
     * <p>
     * Everything the next wait needs is already frozen on the previous step, so
     * no snapshot is required and no live IVR row is consulted.
     */
    private record RetryStep(IvrStep previous, int noInputAttempts) {
        IvrStep build() {
            IvrStep step = new IvrStep();
            step.setTenantId(previous.getTenantId());
            step.setCallSessionId(previous.getCallSessionId());
            step.setCallAttemptId(previous.getCallAttemptId());
            step.setTreeId(previous.getTreeId());
            step.setNodeKey(previous.getNodeKey());
            step.setNodeType(previous.getNodeType());
            step.setInputWaitSeconds(previous.getInputWaitSeconds());
            step.setInvalidInputRetries(previous.getInvalidInputRetries());
            step.setNoInputRetries(previous.getNoInputRetries());
            step.setInvalidAttempts(previous.getInvalidAttempts());
            step.setNoInputAttempts(noInputAttempts);
            step.setResult(IvrStepResultType.WAITING_INPUT);
            step.setExpiresAt(Instant.now().plusSeconds(previous.getInputWaitSeconds()));
            return step;
        }
    }

    /** The atomic claim. Zero rows means someone else already resolved this step. */
    private boolean claim(IvrStep step, IvrStepResultType result, String reason) {
        int claimed = stepRepository.claimTerminal(
                step.getId(), result.name(), reason, Instant.now());
        if (claimed == 0) {
            return false;
        }
        step.setResult(result);
        step.setResultReason(reason);
        step.setResultAt(Instant.now());
        return true;
    }

    // =====================================================================
    // Media
    // =====================================================================

    /**
     * Plays a prompt through the existing media boundary.
     * <p>
     * The URI comes from VB-6E's {@link MediaUriResolver}, never from the raw
     * storage reference: FreeSWITCH resolves a relative play path against its own
     * sound directory, so the application's logical reference is not something it
     * can open. An unusable reference is a permanent configuration fault, recorded
     * through the existing failure path.
     */
    private void playPrompt(CallSession session, UUID audioAssetId, String role) {
        if (audioAssetId == null) {
            return; // a node may legitimately have no prompt for a role
        }
        String unusable = promptGovernance.unusableReason(audioAssetId, session.getTenantId());
        if (unusable != null) {
            log.warn("IVR {} prompt unavailable for callSession {}: {}",
                    role, session.getId(), unusable);
            recordConfigFailure(session, "IVR " + role + " prompt " + unusable);
            return;
        }
        var asset = audioAssetRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(audioAssetId, session.getTenantId());
        if (asset.isEmpty()) {
            recordConfigFailure(session, "IVR prompt audio asset is not available");
            return;
        }
        String mediaUri;
        try {
            mediaUri = mediaUriResolver.resolveMediaUri(
                    asset.get().getStorageReference(), asset.get().getId(),
                    session.getTenantId());
        } catch (IllegalArgumentException e) {
            log.warn("IVR {} prompt has an unusable storage reference: {}", role, e.getMessage());
            recordConfigFailure(session,
                    "IVR prompt audio asset cannot be resolved for playback");
            return;
        }
        try {
            mediaController.playAudio(session.getId(), legIdOf(session), mediaUri);
            log.info("IVR prompt requested (callSession={}, role={}, mediaUri={})",
                    session.getId(), role, mediaUri);
        } catch (RuntimeException e) {
            log.warn("IVR prompt playback failed for callSession {}: {}",
                    session.getId(), e.getMessage());
            session.setFailureCode("IVR_PROMPT_PLAYBACK_FAILED");
            session.setFailureReason("Prompt playback failed: " + e.getMessage());
            callSessionRepository.save(session);
        }
    }

    private void dispatchConnectByAgent(UUID callSessionId, UUID callAttemptId, String reason) {
        var trigger = agentConnectTrigger.getIfAvailable();
        if (trigger == null) {
            callSessionRepository.findByIdAndDeletedAtIsNull(callSessionId).ifPresent(session ->
                    recordConfigFailure(session,
                            "IVR terminal action CONNECT_BY_AGENT is not supported in this "
                                    + "deployment"));
            return;
        }
        try {
            var outcome = trigger.connectByAgent(callSessionId, callAttemptId);
            log.info("IVR CONNECT_BY_AGENT dispatched (callSession={}, selected={}, reason={})",
                    callSessionId, outcome.isSelected(), outcome.reasonCode());
        } catch (RuntimeException e) {
            log.warn("IVR CONNECT_BY_AGENT dispatch failed for callSession {}: {}",
                    callSessionId, e.getMessage());
        }
    }

    private void hangUp(CallSession session, String why) {
        try {
            mediaController.terminateCall(session.getId(), session.getProviderCallId());
            log.info("IVR hangup requested (callSession={}, reason={})", session.getId(), why);
        } catch (RuntimeException e) {
            log.warn("IVR teardown failed for callSession {}: {}", session.getId(), e.getMessage());
        }
    }

    private void recordConfigFailure(CallSession session, String reason) {
        session.setFailureCode(IVR_CONFIG_INVALID_CODE);
        session.setFailureReason(reason);
        callSessionRepository.save(session);
        try {
            mediaController.terminateCall(session.getId(), session.getProviderCallId());
        } catch (RuntimeException e) {
            log.warn("IVR teardown after configuration failure failed for callSession {}: {}",
                    session.getId(), e.getMessage());
        }
    }

    private UUID legIdOf(CallSession session) {
        List<CallLeg> legs = callLegRepository.findByCallSessionIdAndDeletedAtIsNull(session.getId());
        return legs.isEmpty() ? null : legs.get(0).getId();
    }
}
