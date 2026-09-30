package com.shivang.obd.campaign;

import com.shivang.obd.audio.AudioAssetEntity;
import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.dtmf.DtmfCollector;
import com.shivang.obd.voice.dtmf.DtmfConfig;
import com.shivang.obd.voice.dtmf.DtmfConfigInvalidException;
import com.shivang.obd.voice.dtmf.DtmfInteraction;
import com.shivang.obd.voice.dtmf.DtmfInteractionRepository;
import com.shivang.obd.voice.dtmf.DtmfResultService;
import com.shivang.obd.voice.dtmf.DtmfResultType;
import com.shivang.obd.voice.media.DtmfCollectorTrigger;
import com.shivang.obd.voice.media.PlaybackTrigger;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Campaign-side DTMF execution (VB-2).
 * <p>
 * One service owns the whole DTMF interaction because both directions flow
 * through the same trigger boundary ({@link PlaybackTrigger} for lifecycle
 * transitions, {@link DtmfCollectorTrigger} for input events):
 * <ol>
 *   <li>{@link #onAnswered} — PLAYFILE-style playback of the campaign audio
 *       for DTMF campaigns (shared AUDIO asset validation with
 *       {@code PlayfileExecutionService}; DTMF campaigns also play audio)</li>
 *   <li>{@link #onPlaybackCompleted} — validates the typeConfig, creates the
 *       persisted {@link DtmfInteraction} and moves the session to
 *       WAITING_FOR_DTMF</li>
 *   <li>{@link #onDtmfDigit} — appends digits via {@code DtmfCollector},
 *       finalizing VALID/INVALID deterministically</li>
 *   <li>{@link #onDtmfTimeout} — terminalizes the expired interaction as
 *       TIMEOUT via an atomic claim</li>
 * </ol>
 * <p>
 * Every entry point is idempotent: duplicate digits after a terminal result,
 * duplicate timeouts, and events on unrelated session states are no-ops.
 * Only VALID/INVALID/TIMEOUT results hang the call up (via the media
 * boundary — never raw ESL); the authoritative reservation release remains
 * the CHANNEL_HANGUP path (VB-0).
 * <p>
 * Failure classification: {@link #DTMF_CONFIG_INVALID_CODE} is permanent
 * (misconfigured typeConfig — retrying cannot succeed); user-input outcomes
 * (INVALID/TIMEOUT) are call results, not attempt failures — they finalize
 * the call normally and are persisted on the interaction.
 */
@Service
@Lazy
@Slf4j
public class DtmfExecutionService implements PlaybackTrigger, DtmfCollectorTrigger {

    /** DTMF configuration invalid — permanent; retrying cannot succeed. */
    public static final String DTMF_CONFIG_INVALID_CODE = "DTMF_CONFIG_INVALID";

    private final CallSessionRepository callSessionRepository;
    private final CallLegRepository callLegRepository;
    private final CallAttemptRepository callAttemptRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignExecutionRepository executionRepository;
    private final AudioAssetRepository audioAssetRepository;
    /** Execution-scoped configuration resolution (snapshot-first, VB-6A). */
    private final CampaignRuntimeConfigResolver runtimeConfigResolver;
    /** Canonical campaign-resource validation boundary (VB-5E). */
    private final CampaignResourceValidationService resourceValidator;
    private final DtmfInteractionRepository interactionRepository;
    private final DtmfResultService resultService;
    private final VoiceMediaController mediaController;
    /**
     * VB-6F: the IVR runtime. Invoked <em>from</em> this service rather than
     * registered as a second {@code DtmfCollectorTrigger}, so there remains
     * exactly one CHANNEL_DTMF consumer. Optional, so a deployment without IVR
     * support still runs single-level DTMF campaigns unchanged.
     */
    private final ObjectProvider<IvrExecutionService> ivrExecutionService;
    /** VB-6E media URI resolution, applied to the DTMF prompt path as well. */
    private final com.shivang.obd.audio.MediaUriResolver mediaUriResolver;
    /**
     * VB-3: CONNECT_BY_AGENT action boundary — optional because agent-less
     * deployments (no agent domain wired) still run DTMF campaigns with the
     * default TERMINATE action.
     */
    private final ObjectProvider<com.shivang.obd.voice.agent.AgentConnectTrigger> agentConnectTrigger;

    @org.springframework.beans.factory.annotation.Autowired
    public DtmfExecutionService(
            CallSessionRepository callSessionRepository,
            CallLegRepository callLegRepository,
            CallAttemptRepository callAttemptRepository,
            CampaignRepository campaignRepository,
            CampaignExecutionRepository executionRepository,
            AudioAssetRepository audioAssetRepository,
            CampaignResourceValidationService resourceValidator,
            CampaignRuntimeConfigResolver runtimeConfigResolver,
            DtmfInteractionRepository interactionRepository,
            DtmfResultService resultService,
            @Lazy VoiceMediaController mediaController,
            ObjectProvider<IvrExecutionService> ivrExecutionService,
            com.shivang.obd.audio.MediaUriResolver mediaUriResolver,
            ObjectProvider<com.shivang.obd.voice.agent.AgentConnectTrigger> agentConnectTrigger) {
        this.callSessionRepository = callSessionRepository;
        this.callLegRepository = callLegRepository;
        this.callAttemptRepository = callAttemptRepository;
        this.campaignRepository = campaignRepository;
        this.executionRepository = executionRepository;
        this.audioAssetRepository = audioAssetRepository;
        this.resourceValidator = resourceValidator;
        this.runtimeConfigResolver = runtimeConfigResolver;
        this.interactionRepository = interactionRepository;
        this.resultService = resultService;
        this.mediaController = mediaController;
        this.ivrExecutionService = ivrExecutionService;
        this.mediaUriResolver = mediaUriResolver;
        this.agentConnectTrigger = agentConnectTrigger;
    }

    /**
     * The pre-VB-6F shape: no IVR runtime and no media URI resolver.
     * <p>
     * Retained so existing construction sites keep compiling and keep their exact
     * previous meaning. The IVR supplier yields null, so every entry point takes
     * the single-level path exactly as before, and the resolver is a real one over
     * the default {@code audio.storage} configuration rather than a null that
     * would have to be guarded at every playback site.
     */
    public DtmfExecutionService(
            CallSessionRepository callSessionRepository,
            CallLegRepository callLegRepository,
            CallAttemptRepository callAttemptRepository,
            CampaignRepository campaignRepository,
            CampaignExecutionRepository executionRepository,
            AudioAssetRepository audioAssetRepository,
            CampaignResourceValidationService resourceValidator,
            CampaignRuntimeConfigResolver runtimeConfigResolver,
            DtmfInteractionRepository interactionRepository,
            DtmfResultService resultService,
            @Lazy VoiceMediaController mediaController,
            ObjectProvider<com.shivang.obd.voice.agent.AgentConnectTrigger> agentConnectTrigger) {
        this(callSessionRepository, callLegRepository, callAttemptRepository, campaignRepository,
                executionRepository, audioAssetRepository, resourceValidator, runtimeConfigResolver,
                interactionRepository, resultService, mediaController,
                noIvrRuntime(),
                new com.shivang.obd.audio.MediaUriResolver(
                        new com.shivang.obd.audio.AudioStorageProperties()),
                agentConnectTrigger);
    }

    /**
     * An {@link ObjectProvider} that never yields an IVR runtime.
     * <p>
     * Used by the pre-VB-6F constructor so existing call sites keep their exact
     * previous behaviour: every entry point takes the single-level DTMF path,
     * because a DTMF campaign with an IVR snapshot is a VB-6F configuration and
     * cannot reach this constructor in production.
     */
    private static ObjectProvider<IvrExecutionService> noIvrRuntime() {
        return new ObjectProvider<>() {
            @Override
            public IvrExecutionService getObject() {
                throw new IllegalStateException("IVR runtime is not wired in this context");
            }

            @Override
            public IvrExecutionService getObject(Object... args) {
                return getObject();
            }

            @Override
            public IvrExecutionService getIfAvailable() {
                return null;
            }

            @Override
            public IvrExecutionService getIfUnique() {
                return null;
            }
        };
    }

    /**
     * VB-6F: the IVR runtime for this session, or empty when the campaign uses
     * the single-level DTMF path.
     * <p>
     * Every entry point below asks this first, so the single-level code after it
     * is reached unchanged when a campaign has no IVR snapshot. The snapshot is
     * read from the FROZEN execution configuration, never the live campaign.
     */
    private java.util.Optional<IvrExecutionService.IvrCall> ivrFor(
            UUID callSessionId, UUID callAttemptId) {
        if (callAttemptId == null) {
            return java.util.Optional.empty();
        }
        IvrExecutionService ivr = ivrExecutionService.getIfAvailable();
        if (ivr == null) {
            return java.util.Optional.empty();
        }
        return callAttemptRepository.findById(callAttemptId)
                .map(this::resolveExecutionConfig)
                .flatMap(CampaignRuntimeConfigResolver.CampaignRuntimeConfig::asIvr)
                .map(config -> new IvrExecutionService.IvrCall(config, ivr));
    }

    // ------------------------------------------------------------------
    // Lifecycle: answer → playback (same media path as PLAYFILE)
    // ------------------------------------------------------------------

    /**
     * DTMF campaigns play the campaign audio exactly like PLAYFILE ones
     * (shared AUDIO content model). PLAYBACK_STOP then arrives here.
     */
    @Override
    public void onAnswered(UUID callSessionId, UUID callAttemptId) {
        if (callSessionId == null || callAttemptId == null) {
            return;
        }

        Optional<CallSession> sessionOpt = callSessionRepository.findById(callSessionId);
        if (sessionOpt.isEmpty()) {
            log.warn("DTMF trigger: call session {} not found", callSessionId);
            return;
        }
        CallSession session = sessionOpt.get();

        // Idempotency: only transition out of ANSWERED.
        if (session.getStatus() != CallSessionStatus.ANSWERED) {
            log.debug("DTMF trigger: session {} in state {}, ignoring",
                    callSessionId, session.getStatus());
            return;
        }

        Optional<CallAttempt> attemptOpt = callAttemptRepository.findById(callAttemptId);
        if (attemptOpt.isEmpty()) {
            return;
        }
        CallAttempt attempt = attemptOpt.get();

        // Tenant-scoped campaign lookup (isolation): campaignId within the
        // attempt's own tenant.
        Optional<CampaignEntity> campaignOpt = campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(attempt.getCampaignId(), attempt.getTenantId());
        if (campaignOpt.isEmpty()) {
            log.warn("DTMF trigger: campaign {} not found for tenant {}",
                    attempt.getCampaignId(), attempt.getTenantId());
            return;
        }
        // VB-8B (F-02): the campaign row is loaded ONLY to fail closed when it
        // has gone missing, and to keep the lookup tenant-scoped. It is
        // deliberately not bound to a variable: there was a `liveCampaign`
        // binding here that was assigned and never read, which invited exactly
        // the wrong conclusion - that DTMF configuration could come from the
        // campaign. It cannot. Everything below comes from the snapshot.

        // VB-6A: configuration comes from the execution's immutable snapshot.
        // VB-8B correction to the comment that used to stand here: it claimed a
        // "fallback to live config only for legacy executions". There is no such
        // fallback and there must never be one. A missing snapshot is a
        // deterministic integrity failure (ExecutionConfigurationMissingException),
        // not a reason to read mutable campaign state.
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig config =
                resolveExecutionConfig(attempt);
        if (config == null) {
            return;
        }

        // VB-6F: a DTMF campaign may run a reusable IVR tree instead of a single
        // expected sequence. The branch is taken first, so the proven
        // single-level path below is reached byte-for-byte unchanged when a
        // campaign has no IVR snapshot.
        var ivr = ivrFor(callSessionId, callAttemptId);
        if (ivr.isPresent() && ivr.get().runtime() != null) {
            ivr.get().runtime().onAnswered(callSessionId, callAttemptId,
                    ivr.get().config().snapshot());
            return;
        }

        // VB-2: only DTMF campaigns begin collection on this trigger.
        // (PLAYFILE campaigns are handled by PlayfileExecutionService.)
        if (config.campaignType() != CampaignType.DTMF) {
            log.debug("Campaign {} is {} — not DTMF, no DTMF interaction",
                    config.campaignId(), config.campaignType());
            return;
        }

        // DTMF requires AUDIO content mode with an audio asset reference.
        if (config.contentMode() != ContentMode.AUDIO || config.audioAssetId() == null) {
            log.warn("DTMF campaign {} missing AUDIO content/audioAssetId — cannot play",
                    config.campaignId());
            recordConfigFailure(session, "DTMF campaign has no valid audio asset configured");
            return;
        }

        // Tenant isolation + approval + storage semantics come from the
        // canonical resource validation boundary (VB-5E); this boundary maps
        // the outcome onto the existing runtime failure messages. (Snapshot
        // vs. resource rule: the snapshot pins WHAT was requested;
        // approval/ownership/storage stay dynamic — VB-6A §9.)
        var audioResult = resourceValidator.validateAudio(
                config.audioAssetId(), session.getTenantId());
        if (!audioResult.usable()) {
            switch (audioResult.code()) {
                case AUDIO_NOT_AVAILABLE -> {
                    log.warn("Audio asset {} not found for tenant {} (cross-tenant or deleted)",
                            config.audioAssetId(), session.getTenantId());
                    recordConfigFailure(session, "Audio asset is not available for this tenant");
                }
                case AUDIO_NOT_APPROVED -> {
                    log.warn("Audio asset {} is not APPROVED — only approved assets are playable",
                            config.audioAssetId());
                    recordConfigFailure(session, "Audio asset is not approved for playback");
                }
                default -> {
                    log.warn("Audio asset {} has no storage reference — cannot play",
                            config.audioAssetId());
                    recordConfigFailure(session, "Audio asset has no storage reference");
                }
            }
            return;
        }

        AudioAssetEntity asset = audioAssetRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(
                        config.audioAssetId(), session.getTenantId())
                .orElseThrow();

        try {
            // VB-6F: VB-6E fixed this for PLAYFILE (PlayfileExecutionService
            // resolves the logical storage reference into a FreeSWITCH-readable
            // path) but left the DTMF path passing the raw reference, so every
            // DTMF campaign still handed `audio/<tenant>/<asset>/<file>.wav` to
            // uuid_broadcast, which FreeSWITCH resolves against its own sound
            // directory. Same choke point, same fix.
            String mediaUri;
            try {
                mediaUri = mediaUriResolver.resolveMediaUri(
                        asset.getStorageReference(), asset.getId(), session.getTenantId());
            } catch (IllegalArgumentException unusableReference) {
                log.warn("DTMF campaign {} audio asset {} has an unusable storage reference: {}",
                        config.campaignId(), asset.getId(), unusableReference.getMessage());
                recordConfigFailure(session,
                        "Audio asset storage reference cannot be resolved for playback");
                return;
            }
            mediaController.playAudio(session.getId(), legIdOf(session), mediaUri);
            log.info("DTMF campaign playback requested (campaign={}, asset={}, mediaUri={}, "
                            + "callSession={}, attempt={})",
                    config.campaignId(), asset.getId(), mediaUri, session.getId(), callAttemptId);
        } catch (RuntimeException e) {
            log.warn("Playback command failed for callSession {}: {}", session.getId(), e.getMessage());
            session.setFailureCode("DTMF_PLAYBACK_FAILED");
            session.setFailureReason("Playback command failed: " + e.getMessage());
            callSessionRepository.save(session);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle: playback completed → WAITING_FOR_DTMF
    // ------------------------------------------------------------------

    /**
     * PLAYBACK_STOP for a DTMF campaign: validate configuration, persist the
     * interaction, move the session to WAITING_FOR_DTMF. Collection starts
     * only here — digits arriving during playback are ignored.
     */
    @Override
    public void onPlaybackCompleted(UUID callSessionId, UUID callAttemptId) {
        if (callSessionId == null || callAttemptId == null) {
            return;
        }

        Optional<CallSession> sessionOpt = callSessionRepository.findById(callSessionId);
        if (sessionOpt.isEmpty()) {
            return;
        }
        CallSession session = sessionOpt.get();

        // Idempotency: duplicate PLAYBACK_STOP never creates a second
        // interaction or resets an in-flight one.
        if (session.getStatus() != CallSessionStatus.PLAYBACK_COMPLETED) {
            log.debug("DTMF trigger: session {} in state {} — not entering collection",
                    callSessionId, session.getStatus());
            return;
        }

        Optional<CallAttempt> attemptOpt = callAttemptRepository.findById(callAttemptId);
        if (attemptOpt.isEmpty()) {
            return;
        }
        CallAttempt attempt = attemptOpt.get();

        Optional<CampaignEntity> campaignOpt = campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(attempt.getCampaignId(), attempt.getTenantId());
        if (campaignOpt.isEmpty()) {
            return;
        }
        // VB-6A: type decision comes from the execution snapshot config.
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig config =
                resolveExecutionConfig(attempt);
        if (config == null || config.campaignType() != CampaignType.DTMF) {
            return; // Not ours — PLAYFILE teardown is handled elsewhere.
        }

        // VB-6F: for an IVR campaign this arms the wait instead of creating a
        // single-level interaction. Taken before the single-level parse, so a
        // campaign with an IVR snapshot never touches the `expected` contract.
        var ivr = ivrFor(callSessionId, callAttemptId);
        if (ivr.isPresent() && ivr.get().runtime() != null) {
            ivr.get().runtime().onPlaybackCompleted(callSessionId, callAttemptId);
            return;
        }

        // Parse the DTMF configuration exclusively from the execution's
        // snapshot (VB-6A correction): the snapshot is the single source of
        // truth — the live campaign is never consulted. A snapshot whose
        // typed config is missing/corrupt fails deterministically as
        // DTMF_CONFIG_INVALID (permanent — retrying cannot succeed).
        DtmfConfig config2;
        try {
            config2 = config.asDtmf()
                    .map(com.shivang.obd.campaign.config.DtmfCampaignConfig::toDtmfConfig)
                    .orElseThrow(() -> new DtmfConfigInvalidException(
                            "Execution configuration snapshot has no valid DTMF configuration"));
        } catch (DtmfConfigInvalidException e) {
            log.warn("DTMF campaign {} has invalid snapshot typeConfig: {}",
                    config.campaignId(), e.getMessage());
            recordConfigFailure(session, "Invalid DTMF configuration: " + e.getMessage());
            return;
        }

        if (interactionRepository.findByCallSessionIdAndDeletedAtIsNull(session.getId()).isPresent()) {
            log.debug("DTMF interaction already exists for session {} — ignoring duplicate completion",
                    session.getId());
            return;
        }

        Instant now = Instant.now();
        DtmfInteraction interaction = new DtmfInteraction();
        interaction.setTenantId(session.getTenantId());
        interaction.setCallSessionId(session.getId());
        interaction.setCallAttemptId(attempt.getId());
        interaction.setCampaignId(config.campaignId());
        interaction.setExpectedInput(config2.expected());
        interaction.setMaxDigits(config2.maxDigits());
        interaction.setTerminator(config2.getTerminator().orElse(null));
        interaction.setTimeoutSecs(config2.timeoutSecs());
        interaction.setCollectedDigits("");
        interaction.setResult(DtmfResultType.COLLECTING);
        interaction.setExpiresAt(now.plusSeconds(config2.timeoutSecs()));
        interaction.setActionType(config2.action());
        interactionRepository.save(interaction);

        session.setStatus(CallSessionStatus.WAITING_FOR_DTMF);
        callSessionRepository.save(session);

        log.info("DTMF collection started (campaign={}, callSession={}, attempt={}, expected={}, timeoutSecs={})",
                config.campaignId(), session.getId(), attempt.getId(), config2.expected(), config2.timeoutSecs());
    }

    // ------------------------------------------------------------------
    // Input: DTMF digits
    // ------------------------------------------------------------------

    @Override
    public void onDtmfDigit(UUID callSessionId, UUID callAttemptId, String digit) {
        if (callSessionId == null || digit == null || digit.length() != 1) {
            return;
        }

        if (callAttemptId == null) {
            log.debug("DTMF digit on non-campaign session {} — ignoring", callSessionId);
            return;
        }

        // VB-6F: an IVR campaign's digit is resolved against the frozen snapshot
        // by the IVR runtime. Taken before the single-level interaction lookup,
        // which would otherwise find no interaction and silently drop the digit.
        var ivr = ivrFor(callSessionId, callAttemptId);
        if (ivr.isPresent() && ivr.get().runtime() != null) {
            ivr.get().runtime().onDtmfDigit(callSessionId, callAttemptId,
                    ivr.get().config().snapshot(), digit);
            return;
        }

        Optional<DtmfInteraction> interactionOpt =
                interactionRepository.findByCallSessionIdAndDeletedAtIsNull(callSessionId);
        if (interactionOpt.isEmpty()) {
            // No active interaction (digits during playback, already finished,
            // or non-DTMF call): ignore deterministically.
            log.debug("No DTMF interaction for session {} — digit ignored", callSessionId);
            return;
        }
        DtmfInteraction interaction = interactionOpt.get();

        // Idempotency: a terminal interaction never re-acts.
        if (interaction.getResult() != DtmfResultType.COLLECTING) {
            log.debug("DTMF interaction {} already {} — duplicate digit ignored",
                    interaction.getId(), interaction.getResult());
            return;
        }

        // Guard against concurrent/botched finalization: digits cannot
        // meaningfully arrive after the deadline; leave it to the poller.
        if (Instant.now().isAfter(interaction.getExpiresAt())) {
            log.debug("DTMF interaction {} expired — digit ignored (timeout path owns finalization)",
                    interaction.getId());
            return;
        }

        DtmfCollector.FeedResult feed =
                DtmfCollector.feed(DtmfConfig.of(interaction), interaction.getCollectedDigits(), digit.charAt(0));

        if (!feed.terminal()) {
            interaction.setCollectedDigits(feed.collected());
            interactionRepository.save(interaction);
            log.info("DTMF digit collected (callSession={}, collected={}, max={})",
                    callSessionId, feed.collected(), interaction.getMaxDigits());
            return;
        }

        // Terminal result: atomic claim — a racing timeout poller cannot
        // double-finalize, and a duplicate digit event is a no-op. The claim
        // also persists the final collected digits (source of truth for the
        // terminal state), so the audit record shows the whole input.
        Optional<DtmfInteraction> finalized = resultService.finalizeInteraction(
                interaction.getId(), feed.result(), feed.reason(), feed.collected());
        if (finalized.isEmpty()) {
            log.debug("DTMF interaction {} already terminalized — digit result discarded",
                    interaction.getId());
            return;
        }

        log.info("DTMF input {} (callSession={}, collected='{}')",
                feed.result(), callSessionId, feed.collected());
        if (feed.result() == DtmfResultType.VALID || feed.result() == DtmfResultType.INVALID) {
            if (feed.result() == DtmfResultType.VALID
                    && com.shivang.obd.voice.dtmf.DtmfActions.CONNECT_BY_AGENT
                            .equals(interaction.getActionType())) {
                // VB-3: the requested action for a VALID result is an agent
                // connection. The connect trigger owns selection, atomic
                // reservation, agent-leg originate and the bridge; the DTMF
                // layer never touches agent state. Failure of the connect
                // attempt finalizes the call inside the connect service.
                dispatchConnectByAgent(callSessionId, interaction.getCallAttemptId());
                return;
            }
            hangUpCall(callSessionId, "DTMF " + feed.result());
        }
        // TIMEOUT result is finalized by the poller, which hangs up too.
    }

    /**
     * Fires the CONNECT_BY_AGENT action (VB-3). Never throws — the connect
     * service finalizes the call on failure; a missing trigger (agent-less
     * deployment) records a permanent configuration failure.
     */
    private void dispatchConnectByAgent(UUID callSessionId, UUID attemptId) {
        var trigger = agentConnectTrigger.getIfAvailable();
        if (trigger == null) {
            log.warn("CONNECT_BY_AGENT requested for session {} but no agent connect "
                    + "trigger is configured", callSessionId);
            callSessionRepository.findByIdAndDeletedAtIsNull(callSessionId).ifPresent(session ->
                    recordConfigFailure(session,
                            "CONNECT_BY_AGENT action is not supported in this deployment"));
            return;
        }
        try {
            var outcome = trigger.connectByAgent(callSessionId, attemptId);
            log.info("CONNECT_BY_AGENT dispatched (callSession={}, selected={}, reason={})",
                    callSessionId, outcome.isSelected(), outcome.reasonCode());
        } catch (RuntimeException e) {
            log.warn("CONNECT_BY_AGENT dispatch failed for session {}: {}",
                    callSessionId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Input: timeout
    // ------------------------------------------------------------------

    @Override
    public void onDtmfTimeout(UUID callSessionId) {
        // VB-6F: a per-node IVR deadline. Dispatched through the SAME
        // DtmfTimeoutScheduler scan — no second scheduler — and resolved by
        // walking to the session's attempt, because the timeout path is
        // dispatched with a session id only.
        var ivr = ivrForTimeout(callSessionId);
        if (ivr.isPresent() && ivr.get().runtime() != null) {
            ivr.get().runtime().onStepTimeout(callSessionId);
            return;
        }

        Optional<DtmfInteraction> interactionOpt =
                interactionRepository.findByCallSessionIdAndDeletedAtIsNull(callSessionId);
        if (interactionOpt.isEmpty()) {
            return; // Nothing collecting for this call — stale poll row.
        }
        DtmfInteraction interaction = interactionOpt.get();

        // Idempotency guard: an interaction that already reached a result
        // (e.g. a winning digit) is never re-finalized by a later timeout.
        // The atomic claim below remains the race backstop.
        if (interaction.getResult() != DtmfResultType.COLLECTING) {
            log.debug("DTMF interaction {} already {} — timeout ignored",
                    interaction.getId(), interaction.getResult());
            return;
        }

        // Race-safe: the claim decides the winner; collected digits are
        // persisted with the terminal record.
        Optional<DtmfInteraction> finalized = resultService.finalizeInteraction(
                interaction.getId(), DtmfResultType.TIMEOUT, "Collection window expired",
                interaction.getCollectedDigits());
        if (finalized.isEmpty()) {
            return; // Already terminal (a digit won the race) — no-op.
        }

        log.info("DTMF collection timed out (callSession={}, collected='{}')",
                callSessionId, finalized.get().getCollectedDigits());
        hangUpCall(callSessionId, "DTMF TIMEOUT");
    }

    /**
     * VB-6F: resolves the IVR runtime for a timeout dispatch, which carries only
     * a session id. The attempt is found through the session's open IVR step, so
     * no live IVR row is read and no campaign lookup is needed.
     */
    private java.util.Optional<IvrExecutionService.IvrCall> ivrForTimeout(UUID callSessionId) {
        IvrExecutionService ivr = ivrExecutionService.getIfAvailable();
        if (ivr == null) {
            return java.util.Optional.empty();
        }
        return callSessionRepository.findById(callSessionId)
                .map(CallSession::getCallAttemptId)
                .flatMap(callAttemptRepository::findById)
                .map(this::resolveExecutionConfig)
                .flatMap(CampaignRuntimeConfigResolver.CampaignRuntimeConfig::asIvr)
                .map(config -> new IvrExecutionService.IvrCall(config, ivr));
    }

    /**
     * VB-6A correction: resolves the execution's immutable configuration
     * snapshot. Returns null when the execution row is missing — the same
     * tolerate-and-ignore behavior the campaign-missing path has always had.
     * A present execution always resolves its snapshot (mandatory FK); a
     * corrupted association surfaces as
     * {@link ExecutionConfigurationMissingException} to the ESL event loop's
     * existing trigger guard.
     */
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

    private void hangUpCall(UUID callSessionId, String why) {
        try {
            mediaController.terminateCall(callSessionId, null);
            log.info("Hangup requested after DTMF result (callSession={}, reason={})", callSessionId, why);
        } catch (RuntimeException e) {
            // Teardown command failed — the remote party may still hang up
            // (CHANNEL_HANGUP finalizes), or the stale-reservation reconciler
            // reclaims the reservation. Never break the event/poller thread.
            log.warn("Teardown after DTMF result failed for callSession {}: {}",
                    callSessionId, e.getMessage());
        }
    }

    private void recordConfigFailure(CallSession session, String reason) {
        session.setFailureCode(DTMF_CONFIG_INVALID_CODE);
        session.setFailureReason(reason);
        callSessionRepository.save(session);
        try {
            mediaController.terminateCall(session.getId(), session.getProviderCallId());
        } catch (RuntimeException e) {
            log.warn("Teardown after DTMF configuration failure failed for callSession {}: {}",
                    session.getId(), e.getMessage());
        }
    }

    private UUID legIdOf(CallSession session) {
        var legs = callLegRepository.findByCallSessionIdAndDeletedAtIsNull(session.getId());
        return legs.isEmpty() ? null : legs.get(0).getId();
    }
}
