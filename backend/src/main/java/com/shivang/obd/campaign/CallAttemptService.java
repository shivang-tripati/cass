package com.shivang.obd.campaign;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.campaign.dto.CallAttemptResponse;
import com.shivang.obd.campaign.dto.CreateCallAttemptRequest;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.contact.ContactRepository;
import com.shivang.obd.contact.ContactGroupRepository;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Call attempt orchestration boundary service.
 * <p>
 * Queues and manages individual call attempts within a campaign execution.
 * Does not perform actual dialing — that belongs to the future execution engine.
 */
@Service
@RequiredArgsConstructor
public class CallAttemptService {

    private static final String CAP_EXECUTE = "CAMPAIGN_EXECUTE";

    private final CampaignRepository campaignRepository;
    private final CampaignExecutionRepository executionRepository;
    private final CallAttemptRepository attemptRepository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final ContactRepository contactRepository;
    private final ContactGroupRepository contactGroupRepository;
    /** Canonical campaign-resource validation boundary (VB-5E). */
    private final CampaignResourceValidationService resourceValidator;
    /** Reseller hierarchy resolution for the attempt boundary (VB-5F). */
    private final TenantRepository tenantRepository;

    /**
     * Creates a call attempt for an existing execution.
     *
     * @param campaignId the campaign ID
     * @param executionId the execution ID
     * @param request attempt creation request
     * @return attempt response with attempt ID and status
     */
    @Transactional
    public ApiResponse<CallAttemptResponse> createAttempt(UUID campaignId, UUID executionId, CreateCallAttemptRequest request) {
        UUID userId = requireUserId();
        CampaignExecution execution = findVisibleExecution(executionId, currentScope());
        UUID tenantId = execution.getTenantId();

        // Validate campaign-execution relationship
        if (!execution.getCampaignId().equals(campaignId)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "Execution does not belong to the specified campaign");
        }

        authorizationService.requireCapability(userId, CAP_EXECUTE, AccessCheck.forTenant(tenantId));

        // Validate contact exists and belongs to the campaign's contact group
        UUID contactId = request.contactId();
        CampaignEntity campaign = campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.VALIDATION_ERROR,
                        "Campaign not found"));
        // VB-6B.1: contact identity is tenant-scoped (no group predicate);
        // the lookup is tenant-cloaked so a foreign/nonexistent contact is
        // indistinguishable.
        var contact = contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, tenantId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.VALIDATION_ERROR,
                        "Contact does not exist or is not available"));

        // Validate DID exists, is live, ACTIVE and ASSIGNED to the campaign tenant
        // (canonical resource semantics via the VB-5E validation boundary).
        UUID didId = request.didId();
        var didResult = resourceValidator.validateDid(didId, tenantId);
        if (!didResult.usable()) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR,
                    "DID does not exist or is not available for this tenant");
        }

        // Validate attempt number
        Integer attemptNumber = request.attemptNumber();
        if (attemptNumber == null || attemptNumber <= 0) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR,
                    "Attempt number must be positive");
        }

        // Check for duplicate attempt
        if (attemptRepository.existsByExecutionIdAndContactIdAndAttemptNumberAndDeletedAtIsNull(
                executionId, contactId, attemptNumber)) {
            throw new ConflictException(
                    "Call attempt already exists for this execution, contact, and attempt number");
        }

        // Create attempt
        CallAttempt attempt = new CallAttempt();
        attempt.setExecutionId(executionId);
        attempt.setCampaignId(campaignId);
        attempt.setTenantId(tenantId);
        attempt.setContactId(contactId);
        attempt.setDidId(didId);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setScheduledAt(request.scheduledAt() != null ? request.scheduledAt() : Instant.now());

        CallAttempt saved = attemptRepository.save(attempt);
        return ResponseFactory.created(mapToResponse(saved));
    }

    /**
     * Gets a call attempt by ID (scoped to caller's tenant boundary).
     */
    @Transactional(readOnly = true)
    public ApiResponse<CallAttemptResponse> getAttempt(UUID campaignId, UUID executionId, UUID attemptId) {
        UUID userId = requireUserId();
        CampaignExecution execution = findVisibleExecution(executionId, currentScope());

        // Validate campaign-execution relationship
        if (!execution.getCampaignId().equals(campaignId)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "Execution does not belong to the specified campaign");
        }

        authorizationService.requireCapability(userId, CAP_EXECUTE, AccessCheck.forTenant(execution.getTenantId()));

        CallAttempt attempt = findVisibleAttempt(attemptId, execution.getTenantId());

        // Validate attempt belongs to execution
        if (!attempt.getExecutionId().equals(executionId)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "Call attempt does not belong to the specified execution");
        }

        return ResponseFactory.ok(mapToResponse(attempt));
    }

    /**
     * Lists call attempts for an execution (scoped to caller's tenant boundary).
     */
    @Transactional(readOnly = true)
    public ApiResponse<List<CallAttemptResponse>> listAttempts(UUID campaignId, UUID executionId) {
        UUID userId = requireUserId();
        CampaignExecution execution = findVisibleExecution(executionId, currentScope());

        // Validate campaign-execution relationship
        if (!execution.getCampaignId().equals(campaignId)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "Execution does not belong to the specified campaign");
        }

        authorizationService.requireCapability(userId, CAP_EXECUTE, AccessCheck.forTenant(execution.getTenantId()));

        var attempts = attemptRepository.findByExecutionIdAndTenantIdAndDeletedAtIsNullOrderByScheduledAtAsc(
                executionId, execution.getTenantId());

        var responses = attempts.stream()
                .map(this::mapToResponse)
                .toList();

        return ResponseFactory.ok(responses);
    }

    /**
     * Marks an attempt as IN_PROGRESS.
     * Only valid from QUEUED status.
     */
    @Transactional
    public ApiResponse<CallAttemptResponse> markInProgress(UUID campaignId, UUID executionId, UUID attemptId) {
        return transitionStatus(campaignId, executionId, attemptId, CallAttemptStatus.IN_PROGRESS,
                Set.of(CallAttemptStatus.QUEUED), "Cannot mark in progress: attempt is not QUEUED");
    }

    /**
     * Marks an attempt as COMPLETED.
     * Only valid from IN_PROGRESS status.
     */
    @Transactional
    public ApiResponse<CallAttemptResponse> markCompleted(UUID campaignId, UUID executionId, UUID attemptId) {
        return transitionStatus(campaignId, executionId, attemptId, CallAttemptStatus.COMPLETED,
                Set.of(CallAttemptStatus.IN_PROGRESS), "Cannot mark completed: attempt is not IN_PROGRESS");
    }

    /**
     * Marks an attempt as FAILED.
     * Only valid from IN_PROGRESS or QUEUED status.
     */
    @Transactional
    public ApiResponse<CallAttemptResponse> markFailed(UUID campaignId, UUID executionId, UUID attemptId,
            String failureCode, String failureReason) {
        UUID userId = requireUserId();
        CampaignExecution execution = findVisibleExecution(executionId, currentScope());

        // Validate campaign-execution relationship
        if (!execution.getCampaignId().equals(campaignId)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "Execution does not belong to the specified campaign");
        }

        authorizationService.requireCapability(userId, CAP_EXECUTE, AccessCheck.forTenant(execution.getTenantId()));

        CallAttempt attempt = findVisibleAttempt(attemptId, execution.getTenantId());

        // Validate attempt belongs to execution
        if (!attempt.getExecutionId().equals(executionId)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "Call attempt does not belong to the specified execution");
        }

        // Validate transition
        CallAttemptStatus currentStatus = attempt.getStatus();
        if (currentStatus != CallAttemptStatus.IN_PROGRESS && currentStatus != CallAttemptStatus.QUEUED) {
            throw new ConflictException(
                    "Cannot mark failed: attempt is not IN_PROGRESS or QUEUED (current: " + currentStatus + ")");
        }

        attempt.setStatus(CallAttemptStatus.FAILED);
        attempt.setCompletedAt(Instant.now());
        attempt.setFailureCode(failureCode);
        attempt.setFailureReason(failureReason);

        CallAttempt saved = attemptRepository.save(attempt);
        return ResponseFactory.ok(mapToResponse(saved));
    }

    /**
     * Cancels an attempt.
     * Only valid from QUEUED or IN_PROGRESS status.
     */
    @Transactional
    public ApiResponse<CallAttemptResponse> cancel(UUID campaignId, UUID executionId, UUID attemptId) {
        return transitionStatus(campaignId, executionId, attemptId, CallAttemptStatus.CANCELLED,
                Set.of(CallAttemptStatus.QUEUED, CallAttemptStatus.IN_PROGRESS),
                "Cannot cancel: attempt is not QUEUED or IN_PROGRESS");
    }

    // === internal ===

    private ApiResponse<CallAttemptResponse> transitionStatus(
            UUID campaignId, UUID executionId, UUID attemptId,
            CallAttemptStatus newStatus, java.util.Set<CallAttemptStatus> allowedFrom,
            String errorMessage) {
        UUID userId = requireUserId();
        CampaignExecution execution = findVisibleExecution(executionId, currentScope());

        // Validate campaign-execution relationship
        if (!execution.getCampaignId().equals(campaignId)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "Execution does not belong to the specified campaign");
        }

        authorizationService.requireCapability(userId, CAP_EXECUTE, AccessCheck.forTenant(execution.getTenantId()));

        CallAttempt attempt = findVisibleAttempt(attemptId, execution.getTenantId());

        // Validate attempt belongs to execution
        if (!attempt.getExecutionId().equals(executionId)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "Call attempt does not belong to the specified execution");
        }

        // Validate transition
        if (!allowedFrom.contains(attempt.getStatus())) {
            throw new ConflictException(errorMessage + " (current: " + attempt.getStatus() + ")");
        }

        attempt.setStatus(newStatus);
        if (newStatus == CallAttemptStatus.IN_PROGRESS) {
            attempt.setStartedAt(Instant.now());
        } else if (newStatus == CallAttemptStatus.COMPLETED || newStatus == CallAttemptStatus.FAILED || newStatus == CallAttemptStatus.CANCELLED) {
            attempt.setCompletedAt(Instant.now());
        }

        CallAttempt saved = attemptRepository.save(attempt);
        return ResponseFactory.ok(mapToResponse(saved));
    }

    private CampaignExecution findVisibleExecution(UUID executionId, Scope scope) {
        if (scope.tenantId() != null) {
            return executionRepository.findByIdAndTenantIdAndDeletedAtIsNull(executionId, scope.tenantId())
                    .orElseThrow(CallAttemptService::notFound);
        }
        if (scope.resellerId() != null) {
            // Reseller scope is hierarchy-bounded (VB-5F): the previous
            // code passed resellerId as a tenantId, which could never match.
            List<UUID> hierarchyTenants = hierarchyTenantIds(scope.resellerId());
            if (hierarchyTenants.isEmpty()) {
                throw notFound();
            }
            return executionRepository.findByIdAndTenantIdInAndDeletedAtIsNull(executionId, hierarchyTenants)
                    .orElseThrow(CallAttemptService::notFound);
        }
        return executionRepository.findByIdAndDeletedAtIsNull(executionId)
                .orElseThrow(CallAttemptService::notFound);
    }

    /** Active tenants under a reseller; suspended/soft-deleted tenants are excluded. */
    private List<UUID> hierarchyTenantIds(UUID resellerId) {
        return tenantRepository.findAllByResellerIdAndStatus(resellerId, LifecycleStatus.ACTIVE)
                .stream()
                .map(TenantEntity::getId)
                .toList();
    }

    private CallAttempt findVisibleAttempt(UUID attemptId, UUID tenantId) {
        return attemptRepository.findByIdAndTenantIdAndDeletedAtIsNull(attemptId, tenantId)
                .orElseThrow(CallAttemptService::notFound);
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

    private CallAttemptResponse mapToResponse(CallAttempt attempt) {
        return new CallAttemptResponse(
                attempt.getId(),
                attempt.getExecutionId(),
                attempt.getCampaignId(),
                attempt.getTenantId(),
                attempt.getContactId(),
                attempt.getDidId(),
                attempt.getAttemptNumber(),
                attempt.getStatus(),
                attempt.getScheduledAt(),
                attempt.getStartedAt(),
                attempt.getCompletedAt(),
                attempt.getFailureCode(),
                attempt.getFailureReason(),
                attempt.getCreatedAt(),
                attempt.getUpdatedAt()
        );
    }
}