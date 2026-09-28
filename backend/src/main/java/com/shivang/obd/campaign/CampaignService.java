package com.shivang.obd.campaign;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.contact.ContactGroupRepository;
import com.shivang.obd.campaign.config.CampaignTypeConfig;
import com.shivang.obd.campaign.config.ConnectByAgentCampaignConfig;
import com.shivang.obd.campaign.dto.CampaignResponse;
import com.shivang.obd.campaign.dto.CreateCampaignRequest;
import com.shivang.obd.campaign.dto.RetryPolicyConfig;
import com.shivang.obd.campaign.dto.ScheduleConfig;
import com.shivang.obd.campaign.dto.UpdateCampaignRequest;
import com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest;
import com.shivang.obd.campaign.event.CampaignDomainEvent;
import com.shivang.obd.campaign.event.CampaignEventPublisher;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.PaginationMetadata;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Campaign application service. Authorization is enforced here at the
 * service boundary using server-derived organizational context; client
 * supplied tenant identifiers are never trusted. Resource lookups are
 * tenant-boundary constrained inside the query so scoped callers cannot
 * distinguish a foreign campaign from a nonexistent one (IDOR).
 */
@Service
@RequiredArgsConstructor
public class CampaignService {

    private static final String CAP_VIEW = "CAMPAIGN_VIEW";
    private static final String CAP_MANAGE = "CAMPAIGN_MANAGE";
    /** Pre-seeded in V1 for "start, stop, pause and resume campaigns". */
    private static final String CAP_EXECUTE = "CAMPAIGN_EXECUTE";

    /**
     * Legal lifecycle edges. System-driven edges (SCHEDULED→RUNNING,
     * RUNNING→COMPLETED, RUNNING→FAILED) belong to the future execution
     * engine and are rejected on the manual API until that domain exists.
     */
    private static final Map<CampaignStatus, Set<CampaignStatus>> LEGAL_TRANSITIONS = Map.of(
        CampaignStatus.DRAFT, Set.of(CampaignStatus.SCHEDULED),
        CampaignStatus.SCHEDULED, Set.of(CampaignStatus.RUNNING, CampaignStatus.PAUSED,
            CampaignStatus.DRAFT, CampaignStatus.ARCHIVED),
        CampaignStatus.RUNNING, Set.of(CampaignStatus.PAUSED, CampaignStatus.COMPLETED,
            CampaignStatus.FAILED),
        CampaignStatus.PAUSED, Set.of(CampaignStatus.SCHEDULED, CampaignStatus.RUNNING,
            CampaignStatus.ARCHIVED),
        CampaignStatus.COMPLETED, Set.of(CampaignStatus.ARCHIVED),
        CampaignStatus.FAILED, Set.of(CampaignStatus.ARCHIVED),
        CampaignStatus.ARCHIVED, Set.of());

    private static final Set<String> SYSTEM_DRIVEN_TRANSITIONS = Set.of(
        key(CampaignStatus.SCHEDULED, CampaignStatus.RUNNING),
        key(CampaignStatus.RUNNING, CampaignStatus.COMPLETED),
        key(CampaignStatus.RUNNING, CampaignStatus.FAILED));

    /** Explicit sort allowlist; anything else falls back to the default sort. */
    private static final List<String> SORTABLE_FIELDS =
        List.of("name", "createdAt", "updatedAt", "status", "campaignType");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final CampaignRepository repository;
    private final AuthorizationService authorizationService;
    private final CampaignEventPublisher eventPublisher;
    private final CurrentUserProvider currentUserProvider;
    private final CampaignMapper mapper;
    private final TenantRepository tenantRepository;
    private final ContactGroupRepository contactGroupRepository;
    /** Canonical campaign-resource validation boundary (VB-5E). */
    private final CampaignResourceValidationService resourceValidator;
    /** Single editability boundary for all configuration mutation (VB-6A correction). */
    private final CampaignLifecyclePolicy lifecyclePolicy;

    /**
     * Creates a campaign in the caller's context tenant, or — for
     * platform/reseller callers with no single-tenant context — in the
     * explicitly targeted tenant, which must exist and be active.
     */
    @Transactional
    public ApiResponse<CampaignResponse> create(CreateCampaignRequest request, UUID requestedTenantId) {
        UUID userId = requireUserId();
        Scope scope = currentScope();
        UUID tenantId = scope.tenantId() != null ? scope.tenantId() : requestedTenantId;
        if (tenantId == null) {
            throw new BusinessException(
                CommonErrorCode.VALIDATION_ERROR,
                "A tenant must be specified for this operation.");
        }
        requireTargetTenantUsable(tenantId);
        authorizationService.requireCapability(userId, CAP_MANAGE, AccessCheck.forTenant(tenantId));
        validateConfiguration(request.campaignType(), request.contentMode(), request.audioAssetId(),
            request.ttsTemplateId(), request.schedule(), request.retryPolicy(), request.typeConfig());
        validateRunMode(request.runMode(), request.schedule());
        validateContactGroupReference(tenantId, request.contactGroupId());
        validateDidReference(tenantId, request.didId());
        validateContentReferences(tenantId, request.contentMode(),
            request.audioAssetId(), request.ttsTemplateId());
        // VB-7A: queue ownership + administrative lifecycle, the same canonical
        // rule as the DID and content references above.
        validateAgentQueueReference(request.campaignType(), request.typeConfig(), tenantId);
        // VB-6C.2: canonical domain rule for the daily dial limit — guards
        // entities constructed outside REST (DTO validation covers that path;
        // the V48 DB CHECK is the last line of defense).
        DailyDialLimitService.assertConfigurable(request.dailyDialLimit());
        // VB-6D.2: canonical domain rule for retry policy. DTO bean constraints
        // cannot express duplicate categories or an enabled rule with no delay,
        // and this also guards entities built outside REST.
        RetryPolicyValidator.validateView(request.retryPolicy());
        RetryPolicyValidator.validate(mapper.toDomainRetryPolicy(request.retryPolicy()));
        // VB-6D.3: canonical domain rule for the daily campaign-attempt
        // ceiling. DTO validation covers the REST path; this guards entities
        // built outside it; the V51 CHECK is the last line of defense.
        DailyAttemptSafetyService.assertConfigurable(request.maxDailyAttempts());
        // VB-6E: canonical domain rule for the maximum call duration.
        MaxCallDurationPolicy.assertConfigurable(request.maxCallDurationSeconds());

        CampaignEntity entity = mapper.toEntity(request, tenantId);
        CampaignEntity saved = repository.save(entity);
        eventPublisher.publish(CampaignDomainEvent.created(saved));
        return ResponseFactory.created(mapper.toResponse(saved));
    }

    @Transactional(readOnly = true)
    public ApiResponse<CampaignResponse> getById(UUID campaignId) {
        UUID userId = requireUserId();
        CampaignEntity entity = findVisible(campaignId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_VIEW, AccessCheck.forTenant(entity.getTenantId()));
        return ResponseFactory.ok(mapper.toResponse(entity));
    }

    /**
     * Paginated listing implicitly scoped to the caller's organizational
     * boundary: own tenant / own reseller hierarchy / platform-wide.
     */
    @Transactional(readOnly = true)
    public ApiResponse<List<CampaignResponse>> list(
        int page, int size, String[] sortArr, String statusFilter, String typeFilter,
        String runModeFilter, String search
    ) {
        UUID userId = requireUserId();
        Scope scope = currentScope();

        Specification<CampaignEntity> boundary;
        if (scope.tenantId() != null) {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.forTenant(scope.tenantId()));
            boundary = CampaignSpecifications.forTenant(scope.tenantId());
        } else if (scope.resellerId() != null) {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.forReseller(scope.resellerId()));
            List<UUID> hierarchyTenants = hierarchyTenantIds(scope.resellerId());
            if (hierarchyTenants.isEmpty()) {
                return ResponseFactory.page(List.of(), PaginationMetadata.of(page, size, 0));
            }
            boundary = CampaignSpecifications.forTenants(hierarchyTenants);
        } else {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.platformWide());
            boundary = null;
        }

        Specification<CampaignEntity> spec = CampaignSpecifications.compose(
            CampaignSpecifications.notDeleted(),
            boundary,
            statusFilter(statusFilter),
            typeFilter(typeFilter),
            runModeFilter(runModeFilter),
            CampaignSpecifications.search(search));

        Page<CampaignEntity> resultPage = repository.findAll(spec, buildPageable(page, size, sortArr));
        List<CampaignResponse> items = resultPage.getContent().stream().map(mapper::toResponse).toList();
        return ResponseFactory.page(items, PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    @Transactional
    public ApiResponse<CampaignResponse> update(UUID campaignId, UpdateCampaignRequest request) {
        UUID userId = requireUserId();
        CampaignEntity entity = findVisible(campaignId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_MANAGE, AccessCheck.forTenant(entity.getTenantId()));
        // VB-6A correction: configuration mutation is lifecycle-gated in one
        // place — only DRAFT campaigns are editable. A scheduled campaign is
        // unlocked explicitly via the existing SCHEDULED → DRAFT transition
        // before it can be changed again.
        lifecyclePolicy.assertEditable(entity);
        validateConfiguration(entity.getCampaignType(), request.contentMode(), request.audioAssetId(),
            request.ttsTemplateId(), request.schedule(), request.retryPolicy(), request.typeConfig());
        validateRunMode(request.runMode(), request.schedule());
        validateContactGroupReference(entity.getTenantId(), request.contactGroupId());
        validateDidReference(entity.getTenantId(), request.didId());
        validateContentReferences(entity.getTenantId(), request.contentMode(),
            request.audioAssetId(), request.ttsTemplateId());
        // VB-7A: same canonical rule on the update path.
        validateAgentQueueReference(
            entity.getCampaignType(), request.typeConfig(), entity.getTenantId());
        // VB-6C.2: same canonical domain rule on the update path.
        DailyDialLimitService.assertConfigurable(request.dailyDialLimit());
        // VB-6D.2: same canonical domain rule for retry policy.
        RetryPolicyValidator.validateView(request.retryPolicy());
        RetryPolicyValidator.validate(mapper.toDomainRetryPolicy(request.retryPolicy()));
        // VB-6D.3: canonical domain rule for the daily campaign-attempt
        // ceiling. DTO validation covers the REST path; this guards entities
        // built outside it; the V51 CHECK is the last line of defense.
        DailyAttemptSafetyService.assertConfigurable(request.maxDailyAttempts());
        // VB-6E: canonical domain rule for the maximum call duration.
        MaxCallDurationPolicy.assertConfigurable(request.maxCallDurationSeconds());

        mapper.updateEntity(entity, request);
        CampaignEntity saved = repository.save(entity);
        eventPublisher.publish(CampaignDomainEvent.updated(saved));
        return ResponseFactory.ok(mapper.toResponse(saved));
    }

    /** Soft delete: preserves the row, stamps both deletion audit columns. */
    @Transactional
    public void delete(UUID campaignId) {        UUID userId = requireUserId();
        CampaignEntity entity = findVisible(campaignId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_MANAGE, AccessCheck.forTenant(entity.getTenantId()));

        entity.setDeletedAt(Instant.now());
        entity.setDeletedBy(userId.toString());
        repository.save(entity);
        eventPublisher.publish(CampaignDomainEvent.deleted(entity));
    }

    /**
     * Explicit lifecycle transition. Illegal edges and engine-owned
     * (system-driven) transitions are rejected with 409; activation
     * additionally gates on complete, coherent configuration.
     */
    @Transactional
    public ApiResponse<CampaignResponse> changeStatus(UUID campaignId, UpdateCampaignStatusRequest request) {
        UUID userId = requireUserId();
        CampaignStatus target;
        try {
            target = parseEnum(request.status(), CampaignStatus.values());
        } catch (IllegalArgumentException ex) {
            throw business("Unknown status: " + request.status());
        }
        CampaignEntity entity = findVisible(campaignId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_EXECUTE, AccessCheck.forTenant(entity.getTenantId()));

        CampaignStatus from = entity.getStatus();
        if (!LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(target)) {
            throw new ConflictException(
                "Illegal campaign lifecycle transition: " + from + " -> " + target + ".");
        }
        if (SYSTEM_DRIVEN_TRANSITIONS.contains(key(from, target))) {
            throw new ConflictException(
                "Transition " + from + " -> " + target + " is performed by the execution engine.");
        }
        if (from == CampaignStatus.DRAFT && target == CampaignStatus.SCHEDULED) {
            validateActivation(entity);
        }

        entity.setStatus(target);
        CampaignEntity saved = repository.save(entity);
        eventPublisher.publish(CampaignDomainEvent.statusChanged(saved));
        return ResponseFactory.ok(mapper.toResponse(saved));
    }

    /**
     * Creates a fresh DRAFT lineage successor of the source campaign:
     * same tenant, same configuration, version+1, clonedFromCampaignId
     * set. The source is located through the boundary-constrained lookup,
     * so cloning outside the caller's scope is indistinguishable from a
     * missing campaign (404).
     */
    @Transactional
    public ApiResponse<CampaignResponse> clone(UUID sourceCampaignId) {
        UUID userId = requireUserId();
        CampaignEntity source = findVisible(sourceCampaignId, currentScope());
        authorizationService.requireCapability(
            userId, CAP_MANAGE, AccessCheck.forTenant(source.getTenantId()));

        CampaignEntity clone = mapper.cloneOf(source);
        CampaignEntity saved = repository.save(clone);
        eventPublisher.publish(CampaignDomainEvent.cloned(saved));
        return ResponseFactory.created(mapper.toResponse(saved));
    }

    // === internal ===

    /**
     * Caller scope derived exclusively from the server-populated
     * OrganizationContext: TENANT when a home tenant exists, RESELLER
     * otherwise, PLATFORM for unbound (super admin) callers.
     */
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

    /**
     * Boundary-constrained lookup: the caller's tenant restriction is part
     * of the query, so foreign and nonexistent campaigns are both 404.
     */
    private CampaignEntity findVisible(UUID campaignId, Scope scope) {
        if (scope.tenantId() != null) {
            return repository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, scope.tenantId())
                .orElseThrow(CampaignService::notFound);
        }
        if (scope.resellerId() != null) {
            List<UUID> hierarchyTenants = hierarchyTenantIds(scope.resellerId());
            if (hierarchyTenants.isEmpty()) {
                throw notFound();
            }
            return repository.findByIdAndTenantIdInAndDeletedAtIsNull(campaignId, hierarchyTenants)
                .orElseThrow(CampaignService::notFound);
        }
        return repository.findByIdAndDeletedAtIsNull(campaignId)
            .orElseThrow(CampaignService::notFound);
    }

    /** Active tenants under a reseller; suspended/soft-deleted tenants are excluded. */
    private List<UUID> hierarchyTenantIds(UUID resellerId) {
        return tenantRepository.findAllByResellerIdAndStatus(resellerId, LifecycleStatus.ACTIVE)
            .stream()
            .map(TenantEntity::getId)
            .toList();
    }

    private void requireTargetTenantUsable(UUID tenantId) {
        TenantEntity tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
            .orElseThrow(() -> new BusinessException(
                CommonErrorCode.VALIDATION_ERROR, "Referenced tenant does not exist."));
        if (tenant.getStatus() != LifecycleStatus.ACTIVE) {
            throw new BusinessException(
                CommonErrorCode.VALIDATION_ERROR, "Referenced tenant is not active.");
        }
    }

    // === business validation ===

    /**
     * Type-aware configuration rules. DTO validation covers field shapes;
     * these are domain rules:
     * - content selection is exclusive: AUDIO requires an audio asset,
     *   TTS requires an approved template reference;
     * - PLAYFILE/DTMF require content; CONNECT_BY_AGENT does not;
     * - DTMF/CONNECT_BY_AGENT require their (product-defined later)
     *   type-specific configuration payload to be present as a non-empty
     *   JSON object;
     * - schedule combinations must be coherent and timezone-valid;
     * - retry attempts > 0 require a positive interval.
     */
    private void validateConfiguration(
        CampaignType type,
        ContentMode contentMode,
        UUID audioAssetId,
        UUID ttsTemplateId,
        ScheduleConfig schedule,
        RetryPolicyConfig retryPolicy,
        JsonNode typeConfig
    ) {
        validateContent(type, contentMode, audioAssetId, ttsTemplateId);
        validateTypeConfig(type, typeConfig);
        validateSchedule(schedule);
        validateRetry(retryPolicy);
    }

    private void validateContent(
        CampaignType type, ContentMode mode, UUID audioAssetId, UUID ttsTemplateId
    ) {
        boolean hasAudio = audioAssetId != null;
        boolean hasTemplate = ttsTemplateId != null;

        // VB-6E (OD-B): TTS is a governed RESOURCE but there is no TTS
        // synthesis/playback runtime. Before VB-6E a PLAYFILE campaign could be
        // created with contentMode=TTS, pass write-time validation, pass
        // readiness, be scheduled, activated and executed - and then fail EVERY
        // call with PLAYBACK_CONFIG_INVALID, because the PLAYFILE service
        // only knows how to play an audio asset. That is a configuration an
        // operator can build, approve and watch fail 100% of the time, with no
        // way to find out short of reading logs. It is now rejected at
        // configuration time so a campaign can never be created in a state that
        // is guaranteed to fail. TTS playback is a future phase.
        if (type == CampaignType.PLAYFILE && mode == ContentMode.TTS) {
            throw business("PLAYFILE campaigns do not support TTS content yet; "
                + "configure an approved audio asset with content mode AUDIO. "
                + "TTS playback is not implemented.");
        }

        if (mode == ContentMode.AUDIO) {
            if (!hasAudio || hasTemplate) {
                throw business("AUDIO content requires exactly one audio asset reference.");
            }
        } else if (mode == ContentMode.TTS) {
            if (!hasTemplate || hasAudio) {
                throw business("TTS content requires exactly one approved TTS template reference.");
            }
        } else {
            if (hasAudio || hasTemplate) {
                throw business("Content references require an explicit content mode.");
            }
        }

        // VB-7B: the content requirement is stated as an INCLUSIVE list on
        // purpose. The previous form, `type != CONNECT_BY_AGENT`, was correct
        // only while the enum had exactly three values: adding MISSED_CALL
        // would have demanded audio/TTS content from a campaign type that plays
        // no media at all. An inclusive list is safe by construction — a future
        // fifth type is excluded until someone deliberately adds it.
        if ((type == CampaignType.PLAYFILE || type == CampaignType.DTMF) && mode == null) {
            throw business(type + " campaigns require content (audio or TTS).");
        }
    }

    /**
     * VB-7B: every campaign type's {@code typeConfig} is now validated through
     * {@link CampaignTypeConfig#fromTypeConfig}.
     *
     * <p>The previous form enumerated the types that require a payload
     * ({@code DTMF || CONNECT_BY_AGENT}) and returned early for anything else.
     * That is a fail-open list: adding a type to the enum without adding it here
     * would have let an unparseable, legacy or hostile payload through write-time
     * validation, activation and readiness, and the campaign would only fail much
     * later at call time. Delegating instead means the sealed hierarchy is the
     * single authority, and the exhaustive {@code switch} it uses is
     * compiler-enforced — a new campaign type cannot be added without its parse
     * arm.
     *
     * <p>This is strictly stronger than the previous behaviour for the two types
     * that were already listed, and identical for PLAYFILE (whose valid
     * configuration is the empty object).
     */
    private void validateTypeConfig(CampaignType type, JsonNode typeConfig) {
        try {
            com.shivang.obd.campaign.config.CampaignTypeConfig.fromTypeConfig(type, typeConfig);
        } catch (com.shivang.obd.campaign.config.CampaignConfigInvalidException e) {
            throw business(e.getMessage());
        }
    }

    private void validateSchedule(ScheduleConfig schedule) {
        if (schedule == null) {
            return;
        }
        validateScheduleWindow(schedule.startDate(), schedule.endDate(),
            schedule.startTime(), schedule.endTime(), schedule.timezone());
    }

    /** Shared by the DTO path and the entity-based activation gate. */
    private void validateScheduleWindow(
        LocalDate start, LocalDate end, LocalTime startTime, LocalTime endTime, String timezone
    ) {
        if (start != null && end != null && end.isBefore(start)) {
            throw business("Schedule end date must not be before its start date.");
        }
        if (startTime != null && endTime != null && !endTime.isAfter(startTime)) {
            throw business("Daily end time must be after the daily start time.");
        }
        boolean windowConfigured = start != null || end != null || startTime != null || endTime != null;
        if (windowConfigured && (timezone == null || timezone.isBlank())) {
            throw business("Timezone is required when a schedule window is configured.");
        }
        if (timezone != null) {
            try {
                ZoneId.of(timezone.trim());
            } catch (DateTimeException ex) {
                throw business("Timezone must be a valid IANA identifier.");
            }
        }
    }

    /**
     * Run-mode rules: RECURRING campaigns must carry a schedule block so
     * eligibility is defined; ONE_TIME has no extra requirements.
     */
    private void validateRunMode(CampaignRunMode runMode, ScheduleConfig schedule) {
        if (runMode == CampaignRunMode.RECURRING && schedule == null) {
            throw business("RECURRING campaigns require a configured schedule.");
        }
    }

    /**
     * Activation gate for DRAFT -> SCHEDULED: full Phase 2G configuration
     * rules, contact-group ownership invariant, and content-reference
     * validation (live + same-tenant + APPROVED). Number availability and
     * dialer-side checks remain with future modules.
     */
    private void validateActivation(CampaignEntity entity) {
        ScheduleSpec schedule = entity.getSchedule();
        if (schedule == null) {
            throw business("Campaign must have a configured schedule before it can be scheduled.");
        }
        validateContactGroupReference(entity.getTenantId(), entity.getContactGroupId());
        validateDidReference(entity.getTenantId(), entity.getDidId());
        validateContentReferences(entity.getTenantId(), entity.getContentMode(),
            entity.getAudioAssetId(), entity.getTtsTemplateId());
        validateContent(entity.getCampaignType(), entity.getContentMode(),
            entity.getAudioAssetId(), entity.getTtsTemplateId());
        validateTypeConfig(entity.getCampaignType(), entity.getTypeConfig());
        validateAgentQueueReference(
            entity.getCampaignType(), entity.getTypeConfig(), entity.getTenantId());
        validateScheduleWindow(schedule.getStartDate(), schedule.getEndDate(),
            schedule.getStartTime(), schedule.getEndTime(), schedule.getTimezone());
        validateRetrySpec(entity.getRetryPolicy());
    }

    /**
     * VB-7A: CONNECT_BY_AGENT queue ownership and administrative-lifecycle
     * check, applied consistently at write time, at activation, and — through
     * readiness — again at execution creation.
     * <p>
     * {@link #validateTypeConfig} has already proved the type config parses into
     * the typed {@link ConnectByAgentCampaignConfig}, so the queue reference is
     * known to be present and well-formed. What remains is delegated to the
     * canonical {@link CampaignResourceValidationService} and mapped onto the
     * existing non-leaking error — exactly the shape of
     * {@link #validateDidReference}, and with the same rule that
     * SUPER_ADMIN authority does not relax the ownership invariant.
     * <p>
     * Re-checked at activation because it can change after creation, so a
     * campaign whose queue is disabled while it sits SCHEDULED becomes
     * un-executable rather than failing every dial.
     * <p>
     * Live agent availability is <b>not</b> part of this gate. Nobody being
     * available right now is a runtime condition, and blocking a campaign on it
     * would make a correctly configured campaign impossible to save or schedule
     * whenever the contact centre is closed.
     */
    private void validateAgentQueueReference(
            CampaignType type, JsonNode typeConfig, UUID tenantId) {
        if (type != CampaignType.CONNECT_BY_AGENT) {
            return;
        }
        var config = (ConnectByAgentCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                CampaignType.CONNECT_BY_AGENT, typeConfig);
        var result = resourceValidator.validateQueue(config.queueId(), tenantId);
        if (result.usable()) {
            return;
        }
        throw business(result.code() == CampaignResourceValidationService.ValidationCode
                .QUEUE_NOT_ACTIVE
                ? "The configured agent queue is not active."
                : "Agent queue does not exist or is not available.");
    }

    /**
     * Campaign ↔ Contact Group ownership invariant: a supplied reference
     * must point at a live contact group owned by the SAME tenant as the
     * campaign — caller visibility is irrelevant, and SUPER_ADMIN authority
     * does not relax it. Foreign, nonexistent and soft-deleted groups all
     * fail with one non-leaking validation error so no cross-tenant
     * existence information escapes. Absent references remain acceptable:
     * no existing product rule makes a group mandatory for any campaign
     * type yet.
     */
    private void validateContactGroupReference(UUID campaignTenantId, UUID contactGroupId) {
        if (contactGroupId == null) {
            return;
        }
        boolean usable = contactGroupRepository
            .existsByIdAndTenantIdAndDeletedAtIsNull(contactGroupId, campaignTenantId);
        if (!usable) {
            throw business("Contact group does not exist or is not available.");
        }
    }

    /**
     * Campaign ↔ DID ownership invariant: a supplied reference must point at
     * a live DID owned by the SAME tenant as the campaign — caller visibility
     * is irrelevant, and SUPER_ADMIN authority does not relax it. Foreign,
     * nonexistent, soft-deleted, and unusable DIDs all fail with one
     * non-leaking validation error so no cross-tenant existence information
     * escapes. Absent references remain acceptable: no existing product rule
     * makes a DID mandatory for any campaign type yet.
     * <p>
     * A DID is usable by a tenant's campaign when it exists, is not
     * soft-deleted, belongs to the same tenant, and is ACTIVE and ASSIGNED
     * — the canonical VB-5C semantics delegated to
     * {@link CampaignResourceValidationService#validateDid}.
     */
    private void validateDidReference(UUID campaignTenantId, UUID didId) {
        if (didId == null) {
            return;
        }
        if (resourceValidator.validateDid(didId, campaignTenantId).usable()) {
            return;
        }
        throw business("DID does not exist or is not available.");
    }

    /**
     * Content-reference invariant at write time and activation: an
     * AUDIO-mode campaign needs a live, same-tenant, APPROVED audio asset;
     * a TTS-mode campaign needs a usable template — live, APPROVED, and
     * either GLOBAL (platform-owned, usable by every tenant) or TENANT-owned
     * by the campaign tenant (V42). Activation re-checks approval because
     * it can change after creation. Resource semantics are delegated to the
     * canonical {@link CampaignResourceValidationService}; this boundary
     * maps outcomes onto the existing write-time error messages.
     */
    private void validateContentReferences(
        UUID campaignTenantId, ContentMode contentMode, UUID audioAssetId, UUID ttsTemplateId
    ) {
        if (contentMode == ContentMode.AUDIO) {
            if (resourceValidator.validateAudio(audioAssetId, campaignTenantId).usable()) {
                return;
            }
            throw business("Audio asset does not exist or is not approved for use.");
        } else if (contentMode == ContentMode.TTS) {
            if (resourceValidator.validateTts(ttsTemplateId, campaignTenantId).usable()) {
                return;
            }
            throw business("TTS template does not exist or is not approved for use.");
        }
    }

    private void validateRetrySpec(RetryPolicySpec retryPolicy) {
        if (retryPolicy == null) {
            return;
        }
        if (retryPolicy.getMaxAttempts() > 0
            && (retryPolicy.getIntervalSeconds() == null || retryPolicy.getIntervalSeconds() <= 0)) {
            throw business("A positive retry interval is required when retry attempts is greater than zero.");
        }
    }

    private void validateRetry(RetryPolicyConfig retryPolicy) {
        if (retryPolicy == null) {
            return;
        }
        Integer interval = retryPolicy.intervalSeconds();
        if (retryPolicy.maxAttempts() > 0 && (interval == null || interval <= 0)) {
            throw business("A positive retry interval is required when retry attempts is greater than zero.");
        }
    }

    private BusinessException business(String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_ERROR, message);
    }

    // === filters, sorting, actor ===

    private Specification<CampaignEntity> statusFilter(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) {
            return null;
        }
        try {
            return CampaignSpecifications.hasStatus(parseEnum(statusStr, CampaignStatus.values()));
        } catch (IllegalArgumentException ex) {
            throw business("Unknown status filter: " + statusStr);
        }
    }

    private Specification<CampaignEntity> typeFilter(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            return null;
        }
        try {
            return CampaignSpecifications.hasType(parseEnum(typeStr, CampaignType.values()));
        } catch (IllegalArgumentException ex) {
            throw business("Unknown campaignType filter: " + typeStr);
        }
    }

    private Specification<CampaignEntity> runModeFilter(String runModeStr) {
        if (runModeStr == null || runModeStr.isBlank()) {
            return null;
        }
        try {
            return CampaignSpecifications.hasRunMode(parseEnum(runModeStr, CampaignRunMode.values()));
        } catch (IllegalArgumentException ex) {
            throw business("Unknown runMode filter: " + runModeStr);
        }
    }

    private static String key(CampaignStatus from, CampaignStatus to) {
        return from.name() + ">" + to.name();
    }

    private <E extends Enum<E>> E parseEnum(String value, E[] values) {
        String normalized = value.trim().toUpperCase().replace('-', '_');
        for (E candidate : values) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(value);
    }

    /** Allowlisted sort fields; unknown fields fall back to the documented default. */
    private Pageable buildPageable(int page, int size, String[] sortArr) {
        Sort sort = DEFAULT_SORT;
        if (sortArr != null && sortArr.length > 0 && SORTABLE_FIELDS.contains(sortArr[0])) {
            Sort.Direction direction = sortArr.length > 1 && "asc".equalsIgnoreCase(sortArr[1])
                ? Sort.Direction.ASC : Sort.Direction.DESC;
            sort = Sort.by(direction, sortArr[0]);
        }
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort);
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
