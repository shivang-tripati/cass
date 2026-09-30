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
import com.shivang.obd.contact.ContactGroupMemberRepository;
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

    /**
     * VB-8B: {@code CampaignRepository} was removed from this service on purpose.
     * It was injected only to load the live campaign for an existence check that
     * the campaign-execution identifier comparison already covers, and its
     * presence invited exactly the wrong reading - that this service could take
     * execution configuration from the campaign row. It cannot and must not:
     * after snapshot creation the campaign is not a configuration authority.
     */
    private final CampaignExecutionRepository executionRepository;
    private final CallAttemptRepository attemptRepository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final ContactRepository contactRepository;
    private final ContactGroupMemberRepository memberRepository;
    /**
     * VB-8B: the frozen-configuration boundary. Every execution-affecting value
     * this service writes comes from here, never from {@link CampaignEntity}.
     */
    private final CampaignRuntimeConfigResolver runtimeConfigResolver;
    /**
     * VB-8B: the one canonical calling-window calculation, shared with the
     * scheduler so a manual attempt cannot bypass it.
     */
    private final ExecutionScheduleCalculator scheduleCalculator;
    /** Canonical campaign-resource validation boundary (VB-5E). */
    private final CampaignResourceValidationService resourceValidator;
    /** Reseller hierarchy resolution for the attempt boundary (VB-5F). */
    private final TenantRepository tenantRepository;

    /**
     * Creates a call attempt for an existing execution.
     *
     * <h2>VB-8B: what the caller may and may not decide</h2>
     *
     * <p>The request identifies <em>the operation</em> - which execution, which
     * contact, and at which attempt number. It may never redefine <em>the
     * execution's configuration</em>. Every execution-affecting value on the
     * created attempt is therefore taken from, or checked against, the
     * execution's frozen {@link CampaignConfigurationSnapshot}:
     *
     * <ul>
     *   <li>{@code didId} - derived from the snapshot. A supplied value that
     *       disagrees is rejected rather than silently discarded, because there is
     *       exactly one correct DID and a mismatch is always a client error.</li>
     *   <li>{@code scheduledAt} - derived from the snapshot's frozen schedule via
     *       the canonical {@link ExecutionScheduleCalculator}. The request field is
     *       accepted for shape compatibility and is <b>not authoritative</b>;
     *       supplying it cannot place a call outside the campaign's calling hours,
     *       because supplying it changes nothing.</li>
     *   <li>{@code attemptNumber} - bounded by the snapshot's frozen retry policy
     *       through {@link RetryPolicySpec#maxPermittedAttemptNumber()}.</li>
     *   <li>Audience - the contact must be a live member of the snapshot's frozen
     *       contact group, exactly as scheduler-created attempts require.</li>
     *   <li>Runnability - a terminal execution cannot be resurrected.</li>
     * </ul>
     *
     * <p>There is deliberately no "snapshot value if present, else the live
     * campaign" branch anywhere in this method, and the live
     * {@link CampaignEntity} is not loaded at all: the campaign row is not a
     * configuration authority once an execution exists.
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

        // VB-8B: the execution must still be runnable. A terminal execution is
        // finished business; creating a new attempt for it would resurrect work
        // the engine has already closed out.
        assertExecutionRunnable(execution);

        // VB-8B: everything below is sourced from the immutable snapshot, never
        // from the live campaign. There is deliberately no "snapshot if present,
        // else the live campaign" branch anywhere in this method.
        CampaignRuntimeConfigResolver.CampaignRuntimeConfig config =
                runtimeConfigResolver.resolve(execution);

        // VB-6B.1: contact identity is tenant-scoped (no group predicate); the
        // lookup is tenant-cloaked so a foreign/nonexistent contact is
        // indistinguishable. Audience membership is a separate check below.
        UUID contactId = request.contactId();
        var contact = contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, tenantId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.VALIDATION_ERROR,
                        "Contact does not exist or is not available"));

        // VB-8B: audience. The snapshot's group is this execution's audience, and
        // membership stays live (VB-6B.1 Model A). Enforcing it here means a
        // manual attempt is never more privileged than a scheduler-created one:
        // the dial-time check is skipped for whitelist-enforced campaigns, so
        // without this a manual attempt could reach any tenant contact.
        assertWithinFrozenAudience(config, contactId);

        // VB-8B: the DID belongs to the execution. Derive it from the snapshot,
        // and reject a client value that disagrees rather than silently
        // overwriting it - there is exactly one correct DID, so a mismatch is
        // always a client error and never an ambiguity.
        UUID didId = config.didId();
        if (didId == null) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR,
                    "Execution has no DID in its frozen configuration");
        }
        if (request.didId() != null && !request.didId().equals(didId)) {
            throw new ConflictException(
                    "didId is defined by the execution's frozen configuration and cannot be "
                            + "overridden (requested: " + request.didId() + ", execution: " + didId + ").");
        }

        // DID validity (ownership/ACTIVE/ASSIGNED) stays dynamic per the VB-6A
        // snapshot-vs-resource rule: the snapshot pins what was requested, not
        // whether the resource is still usable.
        var didResult = resourceValidator.validateDid(didId, tenantId);
        if (!didResult.usable()) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR,
                    "DID does not exist or is not available for this tenant");
        }

        // VB-8B: attempt numbering is retry-governed and the frozen policy is
        // the only authority. maxRetries counts retries, so the highest
        // permitted attempt number is 1 + maxRetries.
        Integer attemptNumber = request.attemptNumber();
        if (attemptNumber == null || attemptNumber <= 0) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR,
                    "Attempt number must be positive");
        }
        int maxPermitted = config.retryPolicy() == null
                ? 1
                : config.retryPolicy().maxPermittedAttemptNumber();
        if (attemptNumber > maxPermitted) {
            throw new ConflictException(
                    "Attempt number " + attemptNumber + " exceeds this execution's frozen retry "
                            + "policy, which permits at most " + maxPermitted + ".");
        }

        // Check for duplicate attempt
        if (attemptRepository.existsByExecutionIdAndContactIdAndAttemptNumberAndDeletedAtIsNull(
                executionId, contactId, attemptNumber)) {
            throw new ConflictException(
                    "Call attempt already exists for this execution, contact, and attempt number");
        }

        // VB-8B: the dispatch time is execution-owned. Derived from the frozen
        // schedule through the one canonical window calculation, so a manual
        // attempt cannot land outside the campaign's calling hours. The request's
        // scheduledAt is accepted for shape compatibility and is never
        // authoritative - supplying it changes nothing about when we dial.
        Instant scheduledAt = scheduleCalculator
                .calculateNextScheduledAt(config.schedule(), Instant.now());

        // Create attempt
        CallAttempt attempt = new CallAttempt();
        attempt.setExecutionId(executionId);
        attempt.setCampaignId(campaignId);
        attempt.setTenantId(tenantId);
        attempt.setContactId(contactId);
        attempt.setDidId(didId);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setScheduledAt(scheduledAt);
        attempt.setStatus(CallAttemptStatus.QUEUED);

        CallAttempt saved = attemptRepository.save(attempt);
        return ResponseFactory.created(mapToResponse(saved));
    }

    /**
     * VB-8B: execution statuses a manual attempt may be created for.
     *
     * <p>VB-8D: the set now lives on {@link CampaignExecutionStatus}, which is
     * the single authority for the execution lifecycle. The scheduler's dial
     * path asks the same question before dispatching, so keeping a private copy
     * here would have been exactly the drift this avoids.
     */
    private static final Set<CampaignExecutionStatus> ATTEMPT_CREATABLE_EXECUTION_STATUSES =
            CampaignExecutionStatus.DISPATCHABLE;

    /** VB-8B: refuses a manual attempt against a terminal execution. */
    private void assertExecutionRunnable(CampaignExecution execution) {
        if (ATTEMPT_CREATABLE_EXECUTION_STATUSES.contains(execution.getStatus())) {
            return;
        }
        throw new ConflictException(
                "A call attempt cannot be created for an execution in status "
                        + execution.getStatus() + "; only "
                        + ATTEMPT_CREATABLE_EXECUTION_STATUSES + " accept one.");
    }

    /**
     * VB-8B: the contact must belong to the execution's frozen audience.
     *
     * <p>Uses the snapshot's {@code contactGroupId} - the group this execution was
     * created for - through the existing membership bridge. Membership itself
     * stays live, matching VB-6B.1 Model A: the frozen configuration decides
     * <em>which</em> group, live membership decides <em>who</em> is in it.
     */
    private void assertWithinFrozenAudience(
            CampaignRuntimeConfigResolver.CampaignRuntimeConfig config, UUID contactId) {
        UUID frozenGroupId = config.contactGroupId();
        if (frozenGroupId == null) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR,
                    "Execution has no contact group in its frozen configuration");
        }
        if (!memberRepository.existsByContactGroupIdAndContactId(frozenGroupId, contactId)) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR,
                    "Contact is not a member of the execution's frozen contact group");
        }
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