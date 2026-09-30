package com.shivang.obd.campaign;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.campaign.dto.CampaignExecutionResponse;
import com.shivang.obd.campaign.dto.CampaignReadinessReason;
import com.shivang.obd.campaign.dto.CampaignReadinessResponse;
import com.shivang.obd.campaign.dto.ExecuteCampaignRequest;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Campaign execution boundary service.
 * <p>
 * Accepts execution requests, validates readiness, and creates execution records.
 * Does not perform actual execution — that belongs to the future execution engine.
 */
@Service
@RequiredArgsConstructor
public class CampaignExecutionService {

    private static final String CAP_EXECUTE = "CAMPAIGN_EXECUTE";

    private final CampaignRepository campaignRepository;
    private final CampaignExecutionRepository executionRepository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final CampaignReadinessService readinessService;
    /** Reseller hierarchy resolution for the execution boundary (VB-5F). */
    private final TenantRepository tenantRepository;
    /** Immutable execution-configuration materialization (VB-6A). */
    private final CampaignConfigurationService configurationService;

    /**
     * Creates an execution request for a campaign.
     *
     * @param campaignId the campaign to execute
     * @param request execution request (may contain idempotency key)
     * @return execution response with execution ID and status
     */
    @Transactional
    public ApiResponse<CampaignExecutionResponse> execute(UUID campaignId, ExecuteCampaignRequest request) {
        UUID userId = requireUserId();
        CampaignEntity campaign = findVisible(campaignId, currentScope());
        UUID tenantId = campaign.getTenantId();

        authorizationService.requireCapability(userId, CAP_EXECUTE, AccessCheck.forTenant(tenantId));

        // VB-8J: the campaign row is locked so that concurrent creators of THIS
        // campaign serialise. It is a row lock rather than a global or advisory
        // lock, so creators of different campaigns do not contend, and it needs
        // no new infrastructure.
        lockCampaignForExecutionCreation(campaignId, tenantId);

        // Re-check readiness before creating execution
        CampaignReadinessResponse readiness = readinessService.evaluate(campaignId);
        if (!readiness.ready()) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "Campaign is not ready for execution: " + formatReasons(readiness.reasons()));
        }

        // Idempotency check: if key provided, return existing execution or create new
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            var existing = executionRepository
                    .findByCampaignIdAndIdempotencyKeyAndDeletedAtIsNull(campaignId, request.idempotencyKey());
            if (existing.isPresent()) {
                return ResponseFactory.ok(mapToResponse(existing.get()));
            }
        }

        // VB-8J: at most one in-flight execution per campaign.
        //
        // Without this, two concurrent creates each materialise an attempt for
        // EVERY contact in the audience, so the same contact is dialled once per
        // execution. The daily contact and attempt limits would cap the damage
        // but not prevent it, and they are safety ceilings, not intent.
        //
        // Deliberately LAST, after readiness and after the idempotency lookup:
        //
        //  - After idempotency, because retrying a request with the same key is
        //    exactly the case the key exists for. It must return the first
        //    execution, not collide with it.
        //  - After readiness, because readiness reports a fact about the
        //    campaign's configuration, while this reports a fact about execution
        //    state. A disabled queue should still surface as the disabled queue.
        if (executionRepository.existsActiveByCampaignId(campaignId)) {
            throw new BusinessException(
                CommonErrorCode.BUSINESS_RULE_VIOLATION,
                "Campaign already has an execution in progress. Wait for it to reach a "
                    + "terminal state, or pause it, before starting another.");
        }

        // Create the execution's immutable configuration snapshot FIRST
        // (same transaction): the snapshot and execution commit atomically,
        // so no execution exists without its snapshot and no orphan
        // snapshot exists either (VB-6A correction §14). Snapshot creation
        // validates the typeConfig strictly — an invalid configuration
        // fails the execution request deterministically here.
        var configurationSnapshot = configurationService.createExecutionSnapshot(campaign);

        // Create the execution bound to its immutable snapshot. The campaign
        // remains editable per lifecycle; this execution always runs on the
        // configuration it was created with (VB-6A correction core rule).
        CampaignExecution execution = new CampaignExecution();
        execution.setCampaignId(campaignId);
        execution.setTenantId(tenantId);
        execution.setIdempotencyKey(request.idempotencyKey());
        execution.setConfigurationSnapshotId(configurationSnapshot.getId());
        execution.setRequestedAt(Instant.now());
        execution.setRequestedBy(userId.toString());

        CampaignExecution saved = executionRepository.save(execution);
        return ResponseFactory.created(mapToResponse(saved));
    }

    /**
     * Gets an execution by ID (scoped to caller's tenant boundary).
     */
    @Transactional(readOnly = true)
    public ApiResponse<CampaignExecutionResponse> getExecution(UUID executionId) {
        UUID userId = requireUserId();
        CampaignExecution execution = findVisibleExecution(executionId, currentScope());
        authorizationService.requireCapability(userId, CAP_EXECUTE, AccessCheck.forTenant(execution.getTenantId()));
        return ResponseFactory.ok(mapToResponse(execution));
    }

    /**
     * Lists executions for a campaign (scoped to caller's tenant boundary).
     */
    @Transactional(readOnly = true)
    public ApiResponse<java.util.List<CampaignExecutionResponse>> listExecutions(UUID campaignId) {
        UUID userId = requireUserId();
        CampaignEntity campaign = findVisible(campaignId, currentScope());
        authorizationService.requireCapability(userId, CAP_EXECUTE, AccessCheck.forTenant(campaign.getTenantId()));

        var executions = executionRepository
                .findByCampaignIdAndTenantIdAndDeletedAtIsNullOrderByRequestedAtDesc(campaignId, campaign.getTenantId());

        var responses = executions.stream()
                .map(this::mapToResponse)
                .toList();

        return ResponseFactory.ok(responses);
    }

    // === internal ===

    private String formatReasons(List<CampaignReadinessReason> reasons) {
        return reasons.stream()
                .map(r -> r.code() + ": " + r.message())
                .reduce((a, b) -> a + "; " + b)
                .orElse("unknown");
    }

    private CampaignEntity findVisible(UUID campaignId, Scope scope) {
        if (scope.tenantId() != null) {
            return campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, scope.tenantId())
                    .orElseThrow(CampaignExecutionService::notFound);
        }
        if (scope.resellerId() != null) {
            // Reseller scope is hierarchy-bounded (VB-5F); mirrors
            // CampaignService.findVisible so foreign campaigns are 404.
            List<UUID> hierarchyTenants = hierarchyTenantIds(scope.resellerId());
            if (hierarchyTenants.isEmpty()) {
                throw notFound();
            }
            return campaignRepository.findByIdAndTenantIdInAndDeletedAtIsNull(campaignId, hierarchyTenants)
                    .orElseThrow(CampaignExecutionService::notFound);
        }
        return campaignRepository.findByIdAndDeletedAtIsNull(campaignId)
                .orElseThrow(CampaignExecutionService::notFound);
    }

    private CampaignExecution findVisibleExecution(UUID executionId, Scope scope) {
        if (scope.tenantId() != null) {
            return executionRepository.findByIdAndTenantIdAndDeletedAtIsNull(executionId, scope.tenantId())
                    .orElseThrow(CampaignExecutionService::notFound);
        }
        if (scope.resellerId() != null) {
            // Reseller scope is hierarchy-bounded (VB-5F): the previous
            // code passed resellerId as a tenantId, which could never match.
            List<UUID> hierarchyTenants = hierarchyTenantIds(scope.resellerId());
            if (hierarchyTenants.isEmpty()) {
                throw notFound();
            }
            return executionRepository.findByIdAndTenantIdInAndDeletedAtIsNull(executionId, hierarchyTenants)
                    .orElseThrow(CampaignExecutionService::notFound);
        }
        return executionRepository.findByIdAndDeletedAtIsNull(executionId)
                .orElseThrow(CampaignExecutionService::notFound);
    }

    /** Active tenants under a reseller; suspended/soft-deleted tenants are excluded. */
    private List<UUID> hierarchyTenantIds(UUID resellerId) {
        return tenantRepository.findAllByResellerIdAndStatus(resellerId, LifecycleStatus.ACTIVE)
                .stream()
                .map(TenantEntity::getId)
                .toList();
    }

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

    private UUID requireUserId() {
        return currentUserProvider.current()
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.UNAUTHORIZED, "Authentication required."))
                .userId();
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Resource not found");
    }

    /**
     * VB-8J: takes a write lock on the campaign row for the remainder of the
     * creating transaction.
     *
     * <p>A plain existence check would be a read-then-write race: two creators
     * could both observe "no active execution" and both proceed, each
     * materialising an attempt for every contact. Locking the row makes the
     * second creator wait and then observe the first one's committed execution.
     */
    private void lockCampaignForExecutionCreation(UUID campaignId, UUID tenantId) {
        if (executionRepository.lockCampaignRow(campaignId, tenantId) == 0) {
            throw notFound();
        }
    }

    private CampaignExecutionResponse mapToResponse(CampaignExecution execution) {
        return new CampaignExecutionResponse(
                execution.getId(),
                execution.getCampaignId(),
                execution.getTenantId(),
                execution.getStatus(),
                execution.getIdempotencyKey(),
                execution.getConfigurationSnapshotId(),
                execution.getRequestedAt(),
                execution.getRequestedBy(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                execution.getFailureReason(),
                deriveDeferredReason(execution)
        );
    }

    /**
     * VB-8H (B10): why is this execution still {@code REQUESTED}?
     *
     * <p>Derived on read, never persisted, and deliberately limited to
     * {@code REQUESTED} — the only status for which "not started yet" is a real
     * question. {@code RUNNING} has started and terminal statuses are finished,
     * so neither gets a deferred reason and the field can never be mistaken for
     * a second failure reason.
     *
     * <p>An execution is only created while its campaign is ready, so the sole
     * reason one can still be {@code REQUESTED} is that the campaign has since
     * left the executable set. That is a deterministic function of the live
     * campaign status, so it is computed here rather than stored: no migration,
     * no stale column, and no second source of truth to drift. The scheduler
     * applies exactly the same rule via
     * {@code CampaignExecutionOrchestrator.isDeferredRatherThanFailed}, and the
     * message text comes from the single canonical factory in
     * {@link CampaignLifecyclePolicy}, so the two can never disagree.
     *
     * <p>Returns {@code null} when the execution is not deferred, and also when
     * the campaign row cannot be read — a missing campaign is a data-integrity
     * problem that the readiness path already reports, so this stays silent
     * rather than inventing a reason.
     */
    private String deriveDeferredReason(CampaignExecution execution) {
        if (execution.getStatus() != CampaignExecutionStatus.REQUESTED) {
            return null;
        }
        var campaign = campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(
                        execution.getCampaignId(), execution.getTenantId())
                .orElse(null);
        if (campaign == null || CampaignLifecyclePolicy.isExecutable(campaign.getStatus())) {
            return null;
        }
        return CampaignLifecyclePolicy.notExecutableReason(campaign.getStatus()).message();
    }
}