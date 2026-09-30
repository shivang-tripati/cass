package com.shivang.obd.campaign;

import com.shivang.obd.audio.AudioAssetEntity;
import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.audio.MediaUriResolver;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.media.PlaybackTrigger;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Campaign-side PLAYFILE execution (VB-1).
 * <p>
 * Implements {@link PlaybackTrigger} — the telephony event service invokes
 * {@link #onAnswered} when a campaign call is answered. This service:
 * <ol>
 *   <li>Verifies the call belongs to a PLAYFILE campaign with AUDIO content
 *       mode (non-PLAYFILE calls never invoke media playback — P2)</li>
 *   <li>Validates the configured audio asset: exists, resolvable storage
 *       reference, APPROVED, and owned by the call's tenant (P3, P24)</li>
 *   <li>Requests playback through the {@link VoiceMediaController} media
 *       boundary — never through raw ESL commands</li>
 * </ol>
 * <p>
 * Playback completion/failure is event-driven (PLAYBACK_STOP /
 * PLAYBACK_ERROR handled by {@code EslEventService}); command acceptance is
 * NOT treated as playback completion. This service never touches capacity
 * reservations — release stays on the authoritative hangup path.
 * <p>
 * Failure classification: {@link #PLAYBACK_CONFIG_INVALID_CODE} is a
 * permanent configuration error (missing/unapproved asset — retrying cannot
 * succeed); {@link #PLAYBACK_FAILED_CODE} is temporary (transient FreeSWITCH
 * media/resource failure) and follows the existing retry semantics.
 */
@Service
@Lazy
@Slf4j
public class PlayfileExecutionService implements PlaybackTrigger {

    /** Playback failed for a transient (resource/media) reason — retryable. */
    public static final String PLAYBACK_FAILED_CODE = "PLAYBACK_FAILED";

    /** Playback impossible due to campaign/asset configuration — permanent. */
    public static final String PLAYBACK_CONFIG_INVALID_CODE = "PLAYBACK_CONFIG_INVALID";

    private final CallSessionRepository callSessionRepository;
    private final CallLegRepository callLegRepository;
    private final CallAttemptRepository callAttemptRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignExecutionRepository executionRepository;
    private final AudioAssetRepository audioAssetRepository;
    /** Canonical campaign-resource validation boundary (VB-5E). */
    private final CampaignResourceValidationService resourceValidator;
    /** Execution-scoped configuration resolution (immutable snapshot, VB-6A). */
    private final CampaignRuntimeConfigResolver runtimeConfigResolver;
    /** Logical storage reference to FreeSWITCH-readable media path (VB-6E). */
    private final MediaUriResolver mediaUriResolver;
    private final VoiceMediaController mediaController;
    /** VB-7B: the one shared deadline_at authority (see CallSessionDeadlineAuthority). */
    private final CallSessionDeadlineAuthority deadlineAuthority;

    @org.springframework.beans.factory.annotation.Autowired
    public PlayfileExecutionService(
            CallSessionRepository callSessionRepository,
            CallLegRepository callLegRepository,
            CallAttemptRepository callAttemptRepository,
            CampaignRepository campaignRepository,
            CampaignExecutionRepository executionRepository,
            AudioAssetRepository audioAssetRepository,
            CampaignResourceValidationService resourceValidator,
            CampaignRuntimeConfigResolver runtimeConfigResolver,
            MediaUriResolver mediaUriResolver,
            @Lazy VoiceMediaController mediaController,
            CallSessionDeadlineAuthority deadlineAuthority) {
        this.callSessionRepository = callSessionRepository;
        this.callLegRepository = callLegRepository;
        this.callAttemptRepository = callAttemptRepository;
        this.campaignRepository = campaignRepository;
        this.executionRepository = executionRepository;
        this.audioAssetRepository = audioAssetRepository;
        this.resourceValidator = resourceValidator;
        this.runtimeConfigResolver = runtimeConfigResolver;
        this.mediaUriResolver = mediaUriResolver;
        this.mediaController = mediaController;
    this.deadlineAuthority = deadlineAuthority;
    }

    /**
     * The pre-VB-7B constructor, retained so every existing construction site is
     * unchanged. When no shared authority is supplied this service builds one
     * from the session repository it already holds, so behaviour is identical.
     */
    public PlayfileExecutionService(
        CallSessionRepository callSessionRepository,
        CallLegRepository callLegRepository,
        CallAttemptRepository callAttemptRepository,
        CampaignRepository campaignRepository,
        CampaignExecutionRepository executionRepository,
        AudioAssetRepository audioAssetRepository,
        CampaignResourceValidationService resourceValidator,
        CampaignRuntimeConfigResolver runtimeConfigResolver,
        MediaUriResolver mediaUriResolver,
        @Lazy VoiceMediaController mediaController) {
    this(callSessionRepository, callLegRepository, callAttemptRepository, campaignRepository,
        executionRepository, audioAssetRepository, resourceValidator, runtimeConfigResolver,
        mediaUriResolver, mediaController,
        new CallSessionDeadlineAuthority(callSessionRepository));
    }

    /**
     * Called on CHANNEL_ANSWER for campaign calls. Starts PLAYFILE playback
     * when the call belongs to a PLAYFILE+AUDIO campaign with a valid,
     * tenant-owned, approved audio asset.
     */
    @Override
    public void onAnswered(UUID callSessionId, UUID callAttemptId) {
        if (callSessionId == null || callAttemptId == null) {
            return;
        }

        Optional<CallSession> sessionOpt = callSessionRepository.findById(callSessionId);
        if (sessionOpt.isEmpty()) {
            log.warn("Playback trigger: call session {} not found", callSessionId);
            return;
        }
        CallSession session = sessionOpt.get();

        // Idempotency: only transition out of ANSWERED (PLAYING is set by the
        // PLAYBACK_START event, so a re-trigger after that is a no-op).
        if (session.getStatus() != CallSessionStatus.ANSWERED) {
            log.debug("Playback trigger: session {} in state {}, ignoring",
                    callSessionId, session.getStatus());
            return;
        }

        Optional<CallAttempt> attemptOpt = callAttemptRepository.findById(callAttemptId);
        if (attemptOpt.isEmpty()) {
            return;
        }
        CallAttempt attempt = attemptOpt.get();

        // VB-6E: the maximum call duration starts when the call is ANSWERED.
        // Recorded before any playback decision so a call that fails
        // configuration validation is still bounded, and so a call that is
        // played and then runs long is terminated by the reconciler.
        applyMaxCallDuration(callAttemptId, session);

        // Campaign lookup is tenant-scoped (isolation): the attempt's
        // campaignId must resolve within the attempt's own tenant.
        // (campaignId references campaigns; executionId references
        // campaign_executions — using it here never resolves a campaign.)
        Optional<CampaignEntity> campaignOpt = campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(attempt.getCampaignId(), attempt.getTenantId());
        if (campaignOpt.isEmpty()) {
            log.warn("Playback trigger: campaign {} not found for tenant {}",
                    attempt.getCampaignId(), attempt.getTenantId());
            return;
        }
        // VB-8B (F-02): the campaign row is loaded ONLY to fail closed when it
        // has gone missing, and to keep the lookup tenant-scoped. It is
        // deliberately not bound to a variable: there was a `liveCampaign`
        // binding here that was assigned and never read, which invited exactly
        // the wrong conclusion - that playback configuration could come from
        // the campaign. It cannot. Everything below comes from the snapshot.

        // VB-6A correction: configuration comes exclusively from the
        // execution's immutable snapshot — the live campaign (already loaded
        // for existence/isolation above) is never read for configuration.
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig config =
                resolveExecutionConfig(attempt);
        if (config == null) {
            return;
        }

        // P2: only PLAYFILE campaigns play audio in VB-1.
        if (config.campaignType() != CampaignType.PLAYFILE) {
            log.debug("Campaign {} is {} — not PLAYFILE, no playback",
                    config.campaignId(), config.campaignType());
            return;
        }

        // PLAYFILE requires AUDIO content mode with an audio asset reference.
        if (config.contentMode() != ContentMode.AUDIO || config.audioAssetId() == null) {
            log.warn("PLAYFILE campaign {} missing AUDIO content/audioAssetId — cannot play",
                    config.campaignId());
            recordPlaybackFailure(session, PLAYBACK_CONFIG_INVALID_CODE,
                    "PLAYFILE campaign has no valid audio asset configured");
            return;
        }

        // P24 tenant isolation: the asset must be owned by the call's tenant,
        // resolvable, approved, and have a usable storage reference. Resource
        // semantics come from the canonical validation boundary (VB-5E); this
        // boundary maps outcomes onto the existing runtime failure messages.
        // (Snapshot vs. resource rule: the snapshot pins WHAT was requested;
        // approval/ownership/storage stay dynamic — VB-6A correction §9.)
        var audioResult = resourceValidator.validateAudio(
                config.audioAssetId(), session.getTenantId());
        if (!audioResult.usable()) {
            switch (audioResult.code()) {
                case AUDIO_NOT_AVAILABLE -> {
                    log.warn("Audio asset {} not found for tenant {} (cross-tenant or deleted)",
                            config.audioAssetId(), session.getTenantId());
                    recordPlaybackFailure(session, PLAYBACK_CONFIG_INVALID_CODE,
                            "Audio asset is not available for this tenant");
                }
                case AUDIO_NOT_APPROVED -> {
                    log.warn("Audio asset {} is not APPROVED — only approved assets are playable",
                            config.audioAssetId());
                    recordPlaybackFailure(session, PLAYBACK_CONFIG_INVALID_CODE,
                            "Audio asset is not approved for playback");
                }
                default -> {
                    log.warn("Audio asset {} has no storage reference — cannot play",
                            config.audioAssetId());
                    recordPlaybackFailure(session, PLAYBACK_CONFIG_INVALID_CODE,
                            "Audio asset has no storage reference");
                }
            }
            return;
        }

        AudioAssetEntity asset = audioAssetRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(
                        config.audioAssetId(), session.getTenantId())
                .orElseThrow();

        // VB-6E: translate the asset's LOGICAL storage reference into a path
        // FreeSWITCH can actually open. Before VB-6E the raw reference
        // ("audio/{tenant}/{asset}/{file}") was handed to uuid_broadcast, which
        // FreeSWITCH resolves against its own sound directory, so playback
        // could not succeed. The resolver validates shape, tenant and asset
        // identity, and refuses traversal, so a client-supplied reference can
        // never reach the telephony command.
        String mediaUri;
        try {
            mediaUri = mediaUriResolver.resolveMediaUri(
                    asset.getStorageReference(), asset.getId(), session.getTenantId());
        } catch (IllegalArgumentException unusableReference) {
            // A reference that is malformed, names another tenant or another
            // asset, or carries a traversal attempt. Classified as a permanent
            // configuration fault: the same asset would fail identically on
            // every retry, so retrying is pure waste and the campaign operator
            // has to fix the reference.
            log.warn("Audio asset {} has an unusable storage reference for playback: {}",
                    config.audioAssetId(), unusableReference.getMessage());
            recordPlaybackFailure(session, PLAYBACK_CONFIG_INVALID_CODE,
                    "Audio asset storage reference cannot be resolved for playback");
            return;
        }

        // P6 trigger: request playback on the answered call. PLAYBACK_START
        // (→ PLAYING) and PLAYBACK_STOP (→ hangup) arrive as ESL events.
        try {
            mediaController.playAudio(session.getId(), legIdOf(session), mediaUri);
            log.info("PLAYFILE playback requested (campaign={}, asset={}, mediaUri={}, "
                            + "callSession={}, attempt={})",
                    config.campaignId(), asset.getId(), mediaUri, session.getId(), callAttemptId);
        } catch (RuntimeException e) {
            // Command-level failure (channel gone, ESL error). The hangup path
            // will finalize; record why for diagnostics.
            log.warn("Playback command failed for callSession {}: {}", session.getId(), e.getMessage());
            recordPlaybackFailure(session, PLAYBACK_FAILED_CODE,
                    "Playback command failed: " + e.getMessage());
        }
    }

    /**
     * VB-1 post-playback teardown for PLAYFILE campaigns. With VB-2 the
     * completion dispatch is shared: this trigger owns PLAYFILE (hangup after
     * playback) and no-ops for every other campaign type — DTMF campaigns
     * begin collection instead (handled by DtmfExecutionService).
     */
    @Override
    public void onPlaybackCompleted(UUID callSessionId, UUID callAttemptId) {
        Optional<CallSession> sessionOpt = callSessionRepository.findById(callSessionId);
        if (sessionOpt.isEmpty()) {
            return;
        }
        CallSession session = sessionOpt.get();

        // PLAYBACK_STOP reverted nothing; teardown only for live
        // PLAYBACK_COMPLETED sessions of PLAYFILE campaigns. Duplicate
        // completions are no-ops because the session has already left
        // PLAYBACK_COMPLETED once teardown fires.
        if (session.getStatus() != CallSessionStatus.PLAYBACK_COMPLETED) {
            return;
        }

        Optional<CallAttempt> attemptOpt = callAttemptRepository.findById(callAttemptId);
        if (attemptOpt.isEmpty()) {
            return;
        }
        Optional<CampaignEntity> campaignOpt = campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(
                        attemptOpt.get().getCampaignId(), attemptOpt.get().getTenantId());
        if (campaignOpt.isEmpty()) {
            return;
        }
        // VB-6A correction: the type decision comes from the execution's
        // immutable snapshot config.
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig config =
                resolveExecutionConfig(attemptOpt.get());
        if (config == null || config.campaignType() != CampaignType.PLAYFILE) {
            return;
        }

        try {
            mediaController.terminateCall(session.getId(), session.getProviderCallId());
            log.info("PLAYFILE post-playback hangup requested (callSession={})", session.getId());
        } catch (RuntimeException e) {
            log.warn("Post-playback hangup command failed for callSession {}: {}",
                    session.getId(), e.getMessage());
        }
    }

    /**
     * VB-6A correction: resolves the execution's immutable configuration
     * snapshot. Returns null when the execution row is missing — the same
     * tolerate-and-log behavior the campaign-missing path has always had.
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

    private UUID legIdOf(CallSession session) {
        var legs = callLegRepository.findByCallSessionIdAndDeletedAtIsNull(session.getId());
        return legs.isEmpty() ? null : legs.get(0).getId();
    }

    /**
     * VB-6E: records the maximum call duration deadline on the session.
     *
     * <p>Called from the answered path, because the duration is defined as the
     * lifetime of an ESTABLISHED call. The deadline is computed from the
     * <b>frozen snapshot</b> value, never from the live campaign, and is
     * persisted so the reconciler's sweep is an indexed range scan and the
     * timeout is idempotent. A session that already has a deadline is left
     * alone, so a duplicate answer event cannot move it.
     *
     * @return true when a deadline was recorded by this call
     */
    private boolean applyMaxCallDuration(UUID callAttemptId, CallSession session) {
        if (deadlineAuthority.hasDeadline(session)) {
            return false;
        }
        var attemptOpt = callAttemptRepository.findById(callAttemptId);
        if (attemptOpt.isEmpty()) {
            return false;
        }
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig config =
                resolveExecutionConfig(attemptOpt.get());
        if (config == null) {
            return false;
        }
        int seconds = MaxCallDurationPolicy.effectiveSeconds(config.maxCallDurationSeconds());
        // VB-7B: the calculation and the persistence now live in the one shared
        // authority (CallSessionDeadlineAuthority), so MISSED_CALL's ring budget
        // and this cap cannot drift apart. The anchor and the idempotency rule
        // are unchanged: measured from the answer instant, and a session that
        // already carries a deadline is never re-stamped, so a duplicate answer
        // event cannot move it.
        boolean stamped = deadlineAuthority.applyDeadline(
                session, CallSessionDeadlineAuthority.Anchor.ANSWERED, seconds, false);
        if (!stamped) {
            return false;
        }
        session.setAnsweredAt(
                session.getAnsweredAt() != null ? session.getAnsweredAt() : java.time.Instant.now());

        log.info("Call session {} answered - maximum call duration {}s (deadline {})",
                session.getId(), seconds, session.getDeadlineAt());
        return true;
    }

    private void recordPlaybackFailure(CallSession session, String code, String reason) {
        session.setFailureCode(code);
        session.setFailureReason(reason);
        callSessionRepository.save(session);
        // The call is still up; teardown happens via the media boundary so the
        // CHANNEL_HANGUP event can run the authoritative release/finalize path.
        try {
            mediaController.terminateCall(session.getId(), session.getProviderCallId());
        } catch (RuntimeException e) {
            log.warn("Teardown after playback validation failure failed for callSession {}: {}",
                    session.getId(), e.getMessage());
        }
    }
}
