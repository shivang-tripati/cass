package com.shivang.obd.campaign;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.contact.ContactGroupRepository;
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

        List<CampaignReadinessReason> reasons = new ArrayList<>();

        // 1. Lifecycle state check
        checkLifecycleState(campaign, reasons);

        // 2. Schedule readiness (only if schedule is configured)
        if (campaign.getSchedule() != null) {
            checkScheduleReadiness(campaign, reasons);
        }

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

        // VB-6C.1: the Voice Blast daily dial limit computes the usage day
        // in this timezone, so a Voice Blast campaign without one has an
        // undefined day boundary and is not ready to execute. This extends
        // the existing readiness rule ("a window requires a timezone") to
        // the timezone-free always-on case for campaigns whose dial path
        // now depends on an authoritative zone. No JVM/UTC fallback exists
        // at dial time (EXECUTION_TIMEZONE_INVALID), so readiness fails
        // closed here with the same deterministic outcome.
        if (campaign.getCampaignType() == CampaignType.PLAYFILE
                || campaign.getCampaignType() == CampaignType.DTMF
                || campaign.getCampaignType() == CampaignType.CONNECT_BY_AGENT) {
            if (schedule.getTimezone() == null || schedule.getTimezone().isBlank()) {
                reasons.add(new CampaignReadinessReason(
                    "SCHEDULE_TIMEZONE_REQUIRED",
                    "Voice Blast campaigns require a schedule timezone for the "
                        + "daily dial limit; no fallback zone is applied."
                ));
                return;
            }
        }

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

        // PLAYFILE and DTMF require content; CONNECT_BY_AGENT does not
        if (type != CampaignType.CONNECT_BY_AGENT && mode == null) {
            reasons.add(new CampaignReadinessReason(
                "MISSING_REQUIRED_REFERENCE",
                type + " campaigns require content (audio or TTS)."
            ));
        }

        // DTMF and CONNECT_BY_AGENT require type-specific configuration
        if (type == CampaignType.DTMF || type == CampaignType.CONNECT_BY_AGENT) {
            JsonNode typeConfig = campaign.getTypeConfig();
            if (typeConfig == null || typeConfig.isNull() || !typeConfig.isObject() || typeConfig.isEmpty()) {
                reasons.add(new CampaignReadinessReason(
                    "MISSING_REQUIRED_REFERENCE",
                    type + " campaigns require type-specific configuration."
                ));
            }
        }
    }

    /**
     * Contact Group must exist, not be deleted, and belong to the same tenant.
     */
    private void checkContactGroup(CampaignEntity campaign, List<CampaignReadinessReason> reasons) {
        UUID contactGroupId = campaign.getContactGroupId();
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