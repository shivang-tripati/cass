package com.shivang.obd.campaign;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.contact.ContactGroupRepository;
import com.shivang.obd.campaign.config.CampaignConfigInvalidException;
import com.shivang.obd.campaign.config.CampaignTypeConfig;
import com.shivang.obd.campaign.config.ConnectByAgentCampaignConfig;
import com.shivang.obd.campaign.dto.CampaignReadinessReason;
import com.shivang.obd.campaign.dto.CampaignReadinessResponse;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import tools.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Campaign pre-execution readiness evaluation.
 * <p>
 * Pure read-only validation — does not mutate campaign state or trigger execution.
 * Reuses existing ownership/availability invariants for all external references.
 */
@Service
@RequiredArgsConstructor
public class CampaignReadinessService {

    private static final String CAP_VIEW = "CAMPAIGN_VIEW";

    private final CampaignRepository repository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final ContactGroupRepository contactGroupRepository;
    /** Canonical campaign-resource validation boundary (VB-5E). */
    private final CampaignResourceValidationService resourceValidator;
    /** Reseller hierarchy resolution for the readiness boundary (VB-5F). */
    private final TenantRepository tenantRepository;

    /**
     * Evaluates whether a campaign is currently ready for execution.
     * <p>
     * Scoped to the caller's organizational boundary — foreign campaigns
     * are indistinguishable from nonexistent ones (404).
     *
     * @param campaignId the campaign to evaluate
     * @return readiness result with deterministic reasons
     */
    @Transactional(readOnly = true)
    public CampaignReadinessResponse evaluate(UUID campaignId) {
        UUID userId = requireUserId();
        CampaignEntity campaign = findVisible(campaignId, currentScope());
        authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.forTenant(campaign.getTenantId()));
        return evaluateResolved(campaign);
    }

    /**
     * Readiness evaluation for a scheduled (non-interactive) caller (VB-6E).
     *
     * <p>The scheduler has no authenticated user, and
     * {@link #evaluate(UUID)} exists to answer "may <em>this caller</em> see and
     * run this campaign". A scheduled start is a different question: the
     * execution row already carries an authoritative {@code tenantId}, written
     * when the execution was requested through the authenticated API, so the
     * tenant boundary is known without any user context.
     *
     * <p>This is deliberately narrow:
     * <ul>
     *   <li>the campaign is loaded by {@code (campaignId, tenantId)}, so a
     *       foreign campaign is simply not found — tenant isolation is
     *       <em>enforced</em> here, not relaxed;</li>
     *   <li>no user capability is consulted, because there is no user: a
     *       capability check against a fabricated user id would be theatre, and
     *       the interactive path is unchanged and still checked;</li>
     *   <li>the readiness rules themselves are exactly the same
     *       {@link #evaluateResolved} computation, so a scheduled start cannot
     *       pass something an interactive start would refuse.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public CampaignReadinessResponse evaluateForSystem(UUID campaignId, UUID tenantId) {
        CampaignEntity campaign = repository
                .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found"));
        return evaluateResolved(campaign);
    }

    /**
     * The readiness rules, independent of who is asking. Shared verbatim by the
     * interactive and scheduled entry points so the two can never diverge.
     */
    private CampaignReadinessResponse evaluateResolved(CampaignEntity campaign) {
        List<CampaignReadinessReason> reasons = new ArrayList<>();

        // 1. Lifecycle state check
        checkLifecycleState(campaign, reasons);

        // 2. Schedule readiness (only if schedule is configured)
        if (campaign.getSchedule() != null) {
            checkScheduleReadiness(campaign, reasons);
        }

        // 2b. VB-7C.1: the execution-timezone requirement is NOT a schedule
        // concern and was never covered by checkScheduleReadiness. It belongs to
        // the dial path, which is universal: OutboundDialService resolves the
        // VB-6C daily-usage-day zone from the execution snapshot and raises
        // EXECUTION_TIMEZONE_INVALID (a PERMANENT, non-retryable failure) when it
        // is absent - unconditionally, with no campaign-type branch. So a
        // campaign with no timezone is undialable regardless of its type, and a
        // campaign with no schedule object at all is equally undialable.
        //
        // Both were fail-open. The first omitted MISSED_CALL from a type list;
        // the second skipped the whole check because schedule was null. Either
        // way a campaign could be created and reported ready, then fail 100% of
        // calls with no signal beyond a log line. Evaluated here, unfiltered by
        // type, and outside the schedule branch so it cannot be bypassed.
        checkExecutionTimezone(campaign, reasons);

        // 3. Content configuration check
        checkContentConfiguration(campaign, reasons);

        // 4. Contact Group availability
        checkContactGroup(campaign, reasons);

        // 5. DID availability
        checkDid(campaign, reasons);

        // 6. Audio asset approval
        checkAudioAsset(campaign, reasons);

        // 7. TTS template approval
        checkTtsTemplate(campaign, reasons);

        // 7b. VB-7A: CONNECT_BY_AGENT queue configuration. Only for that
        //      campaign type; every other type has nothing to check here.
        checkConnectByAgent(campaign, reasons);

        boolean ready = reasons.isEmpty();
        return new CampaignReadinessResponse(campaign.getId(), ready, reasons);
    }

    /**
     * Campaign must be in an executable lifecycle state.
     * Only SCHEDULED and RUNNING are executable.
     */
    private void checkLifecycleState(CampaignEntity campaign, List<CampaignReadinessReason> reasons) {
        CampaignStatus status = campaign.getStatus();
        if (status != CampaignStatus.SCHEDULED && status != CampaignStatus.RUNNING) {
            reasons.add(new CampaignReadinessReason(
                "CAMPAIGN_NOT_EXECUTABLE_STATE",
                "Campaign is not in an executable state: " + status.name() + ". Only SCHEDULED or RUNNING campaigns can execute."
            ));
        }
    }

    /**
     * Validates that the campaign's schedule is currently eligible for execution.
     * Only checks if schedule is configured — the execution engine owns actual timing.
     */
    private void checkScheduleReadiness(CampaignEntity campaign, List<CampaignReadinessReason> reasons) {
        ScheduleSpec schedule = campaign.getSchedule();

        // A schedule must have at least a timezone if any window is configured
        boolean windowConfigured = schedule.getStartDate() != null
            || schedule.getEndDate() != null
            || schedule.getStartTime() != null
            || schedule.getEndTime() != null;

        if (windowConfigured && (schedule.getTimezone() == null || schedule.getTimezone().isBlank())) {
            reasons.add(new CampaignReadinessReason(
                "INVALID_SCHEDULE",
                "Schedule is missing required timezone."
            ));
            return;
        }

        // Validate IANA timezone if present
        if (schedule.getTimezone() != null && !schedule.getTimezone().isBlank()) {
            try {
                ZoneId.of(schedule.getTimezone().trim());
            } catch (Exception ex) {
                reasons.add(new CampaignReadinessReason(
                    "INVALID_SCHEDULE",
                    "Schedule timezone is not a valid IANA identifier."
                ));
                return;
            }
        }

        // VB-7C.1: the execution-timezone requirement that used to live here
        // has moved to checkExecutionTimezone. It was expressed as a
        // campaign-type membership list (PLAYFILE || DTMF ||
        // CONNECT_BY_AGENT) that omitted MISSED_CALL, and it was skipped
        // entirely when a campaign had no schedule object at all - so a
        // MISSED_CALL campaign, or any campaign without a schedule, could be
        // reported ready and then fail every dial with a PERMANENT
        // EXECUTION_TIMEZONE_INVALID. The requirement is a property of the
        // dial path, not of the campaign type, so it is now evaluated once for
        // every type, outside this method. The window-coherence and
        // IANA-validity rules below are unchanged.

        // If no window is configured, schedule is considered always eligible
        if (!windowConfigured) {
            return;
        }

        // Check current time against schedule window
        ZoneId zone = schedule.getTimezone() != null && !schedule.getTimezone().isBlank()
            ? ZoneId.of(schedule.getTimezone().trim())
            : ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);

        // Date window check
        LocalDate today = now.toLocalDate();
        if (schedule.getStartDate() != null && today.isBefore(schedule.getStartDate())) {
            reasons.add(new CampaignReadinessReason(
                "SCHEDULE_NOT_ELIGIBLE",
                "Campaign schedule has not started yet (starts " + schedule.getStartDate() + ")."
            ));
        }
        if (schedule.getEndDate() != null && today.isAfter(schedule.getEndDate())) {
            reasons.add(new CampaignReadinessReason(
                "SCHEDULE_EXPIRED",
                "Campaign schedule has ended (ended " + schedule.getEndDate() + ")."
            ));
        }

        // Daily time window check
        LocalTime currentTime = now.toLocalTime();
        if (schedule.getStartTime() != null && currentTime.isBefore(schedule.getStartTime())) {
            reasons.add(new CampaignReadinessReason(
                "SCHEDULE_NOT_ELIGIBLE",
                "Current time is before daily start window (" + schedule.getStartTime() + ")."
            ));
        }
        if (schedule.getEndTime() != null && currentTime.isAfter(schedule.getEndTime())) {
            reasons.add(new CampaignReadinessReason(
                "SCHEDULE_NOT_ELIGIBLE",
                "Current time is after daily end window (" + schedule.getEndTime() + ")."
            ));
        }

        // Allowed days of week check
        Set<DayOfWeek> allowedDays = schedule.getAllowedDaysOfWeek();
        if (allowedDays != null && !allowedDays.isEmpty() && !allowedDays.contains(now.getDayOfWeek())) {
            reasons.add(new CampaignReadinessReason(
                "SCHEDULE_NOT_ELIGIBLE",
                "Today is not an allowed execution day."
            ));
        }

        // Holiday calendar is a reference — the owning module resolves it later.
        // We do not evaluate it here.
    }

    /**
     * VB-7C.1: a campaign is only runnable if it carries an authoritative
     * execution timezone.
     *
     * <p><b>Why this is not a campaign-type rule.</b> The VB-6C daily dial limit
     * computes its usage day in the execution snapshot's IANA zone, and
     * {@code OutboundDialService} resolves that zone with
     * {@code campaign.schedule() != null ? campaign.schedule().getTimezone()
     * : null} — <b>no campaign-type branch anywhere on that path</b>. A missing
     * or invalid zone therefore fails the attempt with
     * {@code EXECUTION_TIMEZONE_INVALID}, which {@code CallFailureCode}
     * classifies as {@code PERMANENT}. Every campaign type is dialled, so every
     * campaign type needs the zone.
     *
     * <p><b>The two fail-opens this replaces.</b> The rule previously lived
     * inside {@code checkScheduleReadiness} behind a campaign-type membership
     * list, which meant (a) it was skipped for any type omitted from the list —
     * {@code MISSED_CALL} was, so a MISSED_CALL campaign could be created and
     * reported ready and then fail every dial — and (b) it was skipped entirely
     * whenever {@code schedule == null}, because that whole method is guarded by
     * {@code if (campaign.getSchedule() != null)}. A campaign with no schedule
     * object at all is equally undialable and was equally reported ready.
     *
     * <p><b>No fallback is introduced.</b> The platform deliberately has no
     * JVM/UTC/server-zone default for this value; adopting one here would hide
     * the misconfiguration rather than fail closed, and would make the usage-day
     * boundary differ from the operator's intent. Blank stays unready.
     *
     * <p>Evaluated for every campaign, outside the schedule branch, and with no
     * reference to {@link CampaignType} — so no future type can be omitted from
     * it. IANA validity is still checked separately by
     * {@code checkScheduleReadiness} when a schedule exists, which is unchanged.
     */
    private void checkExecutionTimezone(
            CampaignEntity campaign, List<CampaignReadinessReason> reasons) {
        ScheduleSpec schedule = campaign.getSchedule();
        String timezone = schedule == null ? null : schedule.getTimezone();
        if (timezone == null || timezone.isBlank()) {
            reasons.add(new CampaignReadinessReason(
                "SCHEDULE_TIMEZONE_REQUIRED",
                "Voice Blast campaigns require a schedule timezone for the "
                    + "daily dial limit; no fallback zone is applied."
            ));
        }
    }

    /**
     * Validates content mode matches campaign type requirements.
     */
    private void checkContentConfiguration(CampaignEntity campaign, List<CampaignReadinessReason> reasons) {
        CampaignType type = campaign.getCampaignType();
        ContentMode mode = campaign.getContentMode();
        UUID audioAssetId = campaign.getAudioAssetId();
        UUID ttsTemplateId = campaign.getTtsTemplateId();

        boolean hasAudio = audioAssetId != null;
        boolean hasTemplate = ttsTemplateId != null;

        if (mode == ContentMode.AUDIO) {
            if (!hasAudio || hasTemplate) {
                reasons.add(new CampaignReadinessReason(
                    "INVALID_CONTENT_CONFIGURATION",
                    "AUDIO content requires exactly one audio asset reference."
                ));
            }
        } else if (mode == ContentMode.TTS) {
            if (!hasTemplate || hasAudio) {
                reasons.add(new CampaignReadinessReason(
                    "INVALID_CONTENT_CONFIGURATION",
                    "TTS content requires exactly one approved TTS template reference."
                ));
            }
        } else if (mode != null) {
            // Content mode set but no content references
            reasons.add(new CampaignReadinessReason(
                "INVALID_CONTENT_CONFIGURATION",
                "Content references require an explicit content mode."
            ));
        }

        // VB-7C.1: both media rules are now derived from the type capability
        // rather than from a membership list, matching
        // CampaignService.validateContent exactly.
        //
        // 1. TTS has no runtime. A type that PLAYS MEDIA cannot be configured
        //    with it, or every call would fail with a PERMANENT
        //    PLAYBACK_CONFIG_INVALID. DTMF was previously not covered here (the
        //    write-time guard was PLAYFILE-only), so a DTMF campaign could be
        //    stored with TTS and fail at dial time. Types that play nothing are
        //    deliberately unaffected.
        if (type.playsMedia() && mode == ContentMode.TTS) {
            reasons.add(new CampaignReadinessReason(
                "INVALID_CONTENT_CONFIGURATION",
                type + " campaigns do not support TTS content yet; TTS playback "
                    + "is not implemented. Configure an approved audio asset."
            ));
        }

        // 2. A type that plays media needs something to play.
        if (type.playsMedia() && mode == null) {
            reasons.add(new CampaignReadinessReason(
                "MISSING_REQUIRED_REFERENCE",
                type + " campaigns require content (audio or TTS)."
            ));
        }

        // VB-7B: every type's typeConfig is validated through the sealed
        // hierarchy, replacing the previous fail-open list
        // (`DTMF || CONNECT_BY_AGENT`) which would have skipped MISSED_CALL
        // entirely. Delegating to the exhaustive dispatch means a future type
        // cannot silently bypass readiness validation. An unparseable payload is
        // reported with a type-appropriate reason by the per-type checks below.
        try {
            CampaignTypeConfig.fromTypeConfig(type, campaign.getTypeConfig());
        } catch (CampaignConfigInvalidException e) {
            reasons.add(new CampaignReadinessReason(
                    invalidTypeConfigReasonCode(type),
                    type + " configuration is invalid: " + e.getMessage()));
        }
    }

    /**
     * VB-7B: the readiness reason code for a type configuration that does not
     * parse. Kept in one place so the write-time and readiness surfaces cannot
     * drift, and so a MISSED_CALL campaign is reported as a MISSED_CALL problem
     * rather than being lumped in with an agent or content fault.
     */
    private static String invalidTypeConfigReasonCode(CampaignType type) {
        return switch (type) {
            case MISSED_CALL -> "INVALID_MISSED_CALL_CONFIGURATION";
            case CONNECT_BY_AGENT -> "INVALID_AGENT_CONFIGURATION";
            default -> "INVALID_CONTENT_CONFIGURATION";
        };
    }

    /**
     * VB-7A: a CONNECT_BY_AGENT campaign is ready when its agent connection is
     * <em>configured</em> correctly — not when an agent happens to be free.
     *
     * <p>Three distinct failures are reported separately, because the operator
     * fixes them differently:
     * <ul>
     *   <li>the type config does not parse into the typed CONNECT_BY_AGENT
     *       configuration at all ({@code INVALID_AGENT_CONFIGURATION});</li>
     *   <li>the queue it names is missing, soft-deleted, or owned by another
     *       tenant ({@code AGENT_QUEUE_NOT_AVAILABLE}) — indistinguishable by
     *       design, so this cannot be used to probe a foreign queue;</li>
     *   <li>the queue is the tenant's own but administratively not ACTIVE
     *       ({@code AGENT_QUEUE_NOT_ACTIVE}) — a real own-tenant fact.</li>
     * </ul>
     *
     * <p><b>What is deliberately not checked:</b> whether the queue currently has
     * an available agent, whether every member is at capacity, whether the queue
     * is empty, and how deep it is. All of those are runtime facts that change
     * minute to minute, and a readiness check that failed on them would leave a
     * correctly configured campaign permanently unready whenever the contact
     * centre was closed. A momentary vacancy is reported per call, by the ACD
     * authority, through the existing agent reason codes.
     */
    private void checkConnectByAgent(
            CampaignEntity campaign, List<CampaignReadinessReason> reasons) {
        if (campaign.getCampaignType() != CampaignType.CONNECT_BY_AGENT) {
            return;
        }

        ConnectByAgentCampaignConfig config;
        try {
            config = (ConnectByAgentCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT, campaign.getTypeConfig());
        } catch (CampaignConfigInvalidException e) {
            // VB-7B: already reported by checkContentConfiguration, which now
            // validates every type's payload through the sealed hierarchy.
            // Re-reporting here would duplicate the reason in the response.
            return;
        }
        if (config == null) {
            return;
        }

        // Resource semantics come from the canonical validation boundary (VB-5E,
        // extended by VB-7A); this boundary only maps the outcome onto a reason.
        var queue = resourceValidator.validateQueue(config.queueId(), campaign.getTenantId());
        if (queue.usable()) {
            return;
        }
        switch (queue.code()) {
            case QUEUE_NOT_ACTIVE -> reasons.add(new CampaignReadinessReason(
                    "AGENT_QUEUE_NOT_ACTIVE",
                    "The configured agent queue is not active."));
            default -> reasons.add(new CampaignReadinessReason(
                    "AGENT_QUEUE_NOT_AVAILABLE",
                    "The configured agent queue does not exist or is not available."));
        }
    }

    /**
     * Contact Group must exist, not be deleted, and belong to the same tenant.
     */
    private void checkContactGroup(CampaignEntity campaign, List<CampaignReadinessReason> reasons) {        UUID contactGroupId = campaign.getContactGroupId();
        if (contactGroupId == null) {
            return; // Not mandatory per existing rules
        }
        boolean usable = contactGroupRepository.existsByIdAndTenantIdAndDeletedAtIsNull(
            contactGroupId, campaign.getTenantId());
        if (!usable) {
            reasons.add(new CampaignReadinessReason(
                "CONTACT_GROUP_UNAVAILABLE",
                "Contact group does not exist or is not available."
            ));
        }
    }

    /**
     * DID must exist, not be deleted, belong to the same tenant, be ACTIVE and ASSIGNED.
     * Resource semantics are delegated to the canonical validation boundary;
     * this readiness boundary maps the outcome onto the existing reason.
     */
    private void checkDid(CampaignEntity campaign, List<CampaignReadinessReason> reasons) {
        UUID didId = campaign.getDidId();
        if (didId == null) {
            return; // Not mandatory per existing rules
        }
        var result = resourceValidator.validateDid(didId, campaign.getTenantId());
        if (!result.usable()) {
            reasons.add(new CampaignReadinessReason(
                "DID_UNAVAILABLE",
                "DID does not exist or is not available."
            ));
        }
    }

    /**
     * Audio asset must exist, not be deleted, belong to the same tenant, be
     * APPROVED, and carry a usable storage reference (VB-5B closes the gap
     * where a metadata-only asset activated and then failed per-dial at
     * runtime). Resource semantics are delegated to the canonical validation
     * boundary; this readiness boundary maps each outcome onto the existing
     * reason codes/messages verbatim — including the existing behavior where
     * a missing/foreign asset reports AUDIO_NOT_APPROVED without leaking
     * foreign-row existence.
     */
    private void checkAudioAsset(CampaignEntity campaign, List<CampaignReadinessReason> reasons) {
        UUID audioAssetId = campaign.getAudioAssetId();
        if (audioAssetId == null) {
            return;
        }
        if (campaign.getContentMode() != ContentMode.AUDIO) {
            return; // Only validate when AUDIO mode is selected
        }
        var result = resourceValidator.validateAudio(audioAssetId, campaign.getTenantId());
        if (result.usable()) {
            return;
        }
        if (result.code() == CampaignResourceValidationService.ValidationCode.AUDIO_STORAGE_REFERENCE_MISSING) {
            reasons.add(new CampaignReadinessReason(
                "AUDIO_STORAGE_REFERENCE_MISSING",
                "Audio asset is approved but has no stored audio file."
            ));
        } else {
            // AUDIO_NOT_APPROVED and AUDIO_NOT_AVAILABLE both collapse onto
            // the existing single non-leaking reason.
            reasons.add(new CampaignReadinessReason(
                "AUDIO_NOT_APPROVED",
                "Audio asset does not exist or is not approved for use."
            ));
        }
    }

    /**
     * TTS template must be usable by the campaign tenant (V42): APPROVED,
     * not deleted, and either GLOBAL (platform-owned, usable by every
     * tenant) or TENANT-owned by the campaign tenant.
     *
     * <p>Reasons are classified tenant-safely: only a row the campaign
     * tenant could actually see (own TENANT row, or a GLOBAL row at any
     * status) is reported as {@code TTS_TEMPLATE_NOT_APPROVED}; missing,
     * deleted, or cross-tenant rows report {@code TTS_TEMPLATE_NOT_AVAILABLE}
     * without revealing foreign-row existence.
     */
    private void checkTtsTemplate(CampaignEntity campaign, List<CampaignReadinessReason> reasons) {
        UUID ttsTemplateId = campaign.getTtsTemplateId();
        if (ttsTemplateId == null) {
            return;
        }
        if (campaign.getContentMode() != ContentMode.TTS) {
            return; // Only validate when TTS mode is selected
        }
        var result = resourceValidator.validateTts(ttsTemplateId, campaign.getTenantId());
        if (result.usable()) {
            return;
        }
        if (result.code() == CampaignResourceValidationService.ValidationCode.TTS_NOT_APPROVED) {
            reasons.add(new CampaignReadinessReason(
                "TTS_TEMPLATE_NOT_APPROVED",
                "TTS template is not approved for use."
            ));
        } else {
            reasons.add(new CampaignReadinessReason(
                "TTS_TEMPLATE_NOT_AVAILABLE",
                "TTS template does not exist or is not available to this campaign."
            ));
        }
    }

    // === internal ===

    private record Scope(UUID tenantId, UUID resellerId) {
        static Scope of(com.shivang.obd.authz.context.OrganizationContext ctx) {
            return ctx == null
                ? new Scope(null, null)
                : new Scope(ctx.tenantId(), ctx.resellerId());
        }
    }

    private Scope currentScope() {
        return Scope.of(OrganizationContextHolder.current().orElse(null));
    }

    private CampaignEntity findVisible(UUID campaignId, Scope scope) {
        if (scope.tenantId() != null) {
            return repository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, scope.tenantId())
                .orElseThrow(CampaignReadinessService::notFound);
        }
        if (scope.resellerId() != null) {
            // Reseller scope is hierarchy-bounded (VB-5F): the caller's
            // reseller restriction is part of the query, so a campaign
            // outside the hierarchy is indistinguishable from a missing
            // one (404). Mirrors CampaignService.findVisible.
            List<UUID> hierarchyTenants = hierarchyTenantIds(scope.resellerId());
            if (hierarchyTenants.isEmpty()) {
                throw notFound();
            }
            return repository.findByIdAndTenantIdInAndDeletedAtIsNull(campaignId, hierarchyTenants)
                .orElseThrow(CampaignReadinessService::notFound);
        }
        return repository.findByIdAndDeletedAtIsNull(campaignId)
            .orElseThrow(CampaignReadinessService::notFound);
    }

    /** Active tenants under a reseller; suspended/soft-deleted tenants are excluded. */
    private List<UUID> hierarchyTenantIds(UUID resellerId) {
        return tenantRepository.findAllByResellerIdAndStatus(resellerId, LifecycleStatus.ACTIVE)
            .stream()
            .map(TenantEntity::getId)
            .toList();
    }

    private UUID requireUserId() {
        return currentUserProvider.current()
            .orElseThrow(() -> new BusinessException(
                CommonErrorCode.UNAUTHORIZED, "Authentication required."))
            .userId();
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Campaign not found");
    }
}