package com.shivang.obd.voice.queue;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.PaginationMetadata;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.agent.Agent;
import com.shivang.obd.voice.agent.AgentRepository;
import com.shivang.obd.voice.queue.dto.AddQueueMemberRequest;
import com.shivang.obd.voice.queue.dto.CreateQueueRequest;
import com.shivang.obd.voice.queue.dto.QueueCapacityResponse;
import com.shivang.obd.voice.queue.dto.QueueMemberResponse;
import com.shivang.obd.voice.queue.dto.QueueResponse;
import com.shivang.obd.voice.queue.dto.QueueWaitingCallResponse;
import com.shivang.obd.voice.queue.dto.UpdateQueueMemberRequest;
import com.shivang.obd.voice.queue.dto.UpdateQueueRequest;
import com.shivang.obd.voice.queue.dto.UpdateQueueStatusRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-4B Queue Foundation application service.
 *
 * <p>Queue lifecycle (create/get/list/update/status), configuration
 * (capacity, timeout, overflow — persisted only, never executed),
 * membership management (race-safe via the partial unique index), and
 * the waiting-call read model over the canonical {@code CallSession}.</p>
 *
 * <p>Out of scope (deliberately): agent selection/ACD, queue dispatch,
 * timeout/overflow execution, inbound calling, outbound agent calling.
 * VB-4C+ consume this foundation.</p>
 *
 * <p>All operations are tenant-scoped and capability-checked at this
 * boundary ({@code QUEUE_VIEW}/{@code QUEUE_MANAGE} — seeded in V37,
 * granted to SUPER_ADMIN/RESELLER_ADMIN/TENANT_ADMIN).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueueDirectoryService {

    private static final String CAP_VIEW = "QUEUE_VIEW";
    private static final String CAP_MANAGE = "QUEUE_MANAGE";

    private static final List<String> QUEUE_SORTABLE_FIELDS =
            List.of("name", "createdAt", "updatedAt");
    private static final Sort DEFAULT_QUEUE_SORT =
            Sort.by(Sort.Direction.ASC, "name");
    private static final int MAX_PAGE_SIZE = 100;

    private final QueueRepository queueRepository;
    private final QueueMembershipRepository membershipRepository;
    private final QueueWaitingCallRepository waitingCallRepository;
    private final AgentRepository agentRepository;
    private final TenantRepository tenantRepository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final jakarta.persistence.EntityManager entityManager;

    // === queue lifecycle ===

    /** Creates a queue in the caller's tenant. New queues start ACTIVE. */
    @Transactional
    public ApiResponse<QueueResponse> createQueue(CreateQueueRequest request) {
        UUID userId = requireUserId();
        Scope scope = currentScope();
        UUID tenantId = requireTenant(scope);
        requireTenantUsable(tenantId);
        authorizationService.requireCapability(userId, CAP_MANAGE, AccessCheck.forTenant(tenantId));

        String name = request.name().trim();
        if (queueRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(tenantId, name)) {
            throw new ConflictException(
                    "A queue with this name already exists in this tenant.");
        }
        Queue queue = new Queue();
        queue.setTenantId(tenantId);
        queue.setName(name);
        queue.setDescription(request.description() != null
                ? request.description().trim() : null);
        if (request.maxWaitingCalls() != null) {
            queue.setMaxWaitingCalls(request.maxWaitingCalls());
        }
        if (request.maxWaitSeconds() != null) {
            queue.setMaxWaitSeconds(request.maxWaitSeconds());
        }
        applyOverflow(queue,
                request.overflowEnabled() != null && request.overflowEnabled(),
                request.overflowQueueId(), tenantId);
        Queue saved = queueRepository.save(queue);
        log.info("Queue created (queue={}, tenant={})", saved.getId(), tenantId);
        return ResponseFactory.created(toResponse(saved));
    }

    /** Tenant-scoped read; foreign and nonexistent queues are indistinguishable (404). */
    @Transactional(readOnly = true)
    public ApiResponse<QueueResponse> getQueue(UUID queueId) {
        UUID userId = requireUserId();
        Queue queue = findVisible(queueId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forTenant(queue.getTenantId()));
        return ResponseFactory.ok(toResponse(queue));
    }

    /** Paginated queue listing scoped to the caller's organizational boundary. */
    @Transactional(readOnly = true)
    public ApiResponse<List<QueueResponse>> listQueues(
            int page, int size, String[] sortArr, String search) {
        UUID userId = requireUserId();
        Scope scope = currentScope();

        Specification<Queue> boundary;
        if (scope.tenantId() != null) {
            authorizationService.requireCapability(
                    userId, CAP_VIEW, AccessCheck.forTenant(scope.tenantId()));
            boundary = (root, query, cb) ->
                    cb.equal(root.get("tenantId"), scope.tenantId());
        } else if (scope.resellerId() != null) {
            authorizationService.requireCapability(
                    userId, CAP_VIEW, AccessCheck.forReseller(scope.resellerId()));
            List<UUID> hierarchyTenants = hierarchyTenantIds(scope.resellerId());
            if (hierarchyTenants.isEmpty()) {
                return ResponseFactory.page(List.of(), PaginationMetadata.of(page, size, 0));
            }
            boundary = (root, query, cb) -> root.get("tenantId").in(hierarchyTenants);
        } else {
            authorizationService.requireCapability(userId, CAP_VIEW, AccessCheck.platformWide());
            boundary = null;
        }

        Specification<Queue> spec = QueueSpecifications.compose(
                QueueSpecifications.notDeleted(), boundary,
                QueueSpecifications.search(search));

        Page<Queue> resultPage = queueRepository.findAll(
                spec, buildPageable(page, size, sortArr, DEFAULT_QUEUE_SORT, QUEUE_SORTABLE_FIELDS));
        List<QueueResponse> items = resultPage.getContent().stream()
                .map(this::toResponse).toList();
        return ResponseFactory.page(items,
                PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    /** Updates mutable queue configuration. Overflow rules are re-validated. */
    @Transactional
    public ApiResponse<QueueResponse> updateQueue(UUID queueId, UpdateQueueRequest request) {
        UUID userId = requireUserId();
        Queue queue = findVisible(queueId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(queue.getTenantId()));

        if (request.name() != null) {
            String name = request.name().trim();
            if (!name.equalsIgnoreCase(queue.getName())
                    && queueRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(
                            queue.getTenantId(), name)) {
                throw new ConflictException(
                        "A queue with this name already exists in this tenant.");
            }
            queue.setName(name);
        }
        if (request.description() != null) {
            queue.setDescription(request.description().trim());
        }
        if (request.maxWaitingCalls() != null) {
            queue.setMaxWaitingCalls(request.maxWaitingCalls());
        }
        if (request.maxWaitSeconds() != null) {
            queue.setMaxWaitSeconds(request.maxWaitSeconds());
        }
        if (request.overflowEnabled() != null || request.overflowQueueId() != null) {
            boolean enabled = request.overflowEnabled() != null
                    ? request.overflowEnabled() : queue.isOverflowEnabled();
            UUID targetId = request.overflowQueueId() != null
                    ? request.overflowQueueId() : queue.getOverflowQueueId();
            applyOverflow(queue, enabled, targetId, queue.getTenantId());
        }
        log.info("Queue updated (queue={}, tenant={})", queueId, queue.getTenantId());
        return ResponseFactory.ok(toResponse(queue));
    }

    /**
     * Administrative lifecycle transition. Valid transitions:
     * ACTIVE→INACTIVE, INACTIVE→ACTIVE, ACTIVE|INACTIVE→DISABLED.
     * DISABLED is terminal. Setting the current status is idempotent.
     */
    @Transactional
    public ApiResponse<QueueResponse> updateStatus(UUID queueId, UpdateQueueStatusRequest request) {
        UUID userId = requireUserId();
        Queue queue = findVisible(queueId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(queue.getTenantId()));

        QueueStatus target = request.status();
        if (target == queue.getStatus()) {
            return ResponseFactory.ok(toResponse(queue)); // idempotent
        }
        if (queue.getStatus() == QueueStatus.DISABLED) {
            throw new ConflictException(
                    "Queue is DISABLED; DISABLED is a terminal queue status.");
        }
        if (target == QueueStatus.DISABLED
                || (queue.getStatus() == QueueStatus.ACTIVE && target == QueueStatus.INACTIVE)
                || (queue.getStatus() == QueueStatus.INACTIVE && target == QueueStatus.ACTIVE)) {
            queue.setStatus(target);
            log.info("Queue status changed (queue={}, tenant={}, status={})",
                    queueId, queue.getTenantId(), target);
            return ResponseFactory.ok(toResponse(queue));
        }
        throw business("Invalid queue status transition: "
                + queue.getStatus() + " -> " + target + ".");
    }

    // === capacity read model ===

    /**
     * Configured capacity vs the live WAITING count — a report of current
     * state, not an admission decision (admission/dispatch belong to
     * VB-4C/4D). Counting derives from canonical waiting-call rows.
     */
    @Transactional(readOnly = true)
    public ApiResponse<QueueCapacityResponse> getCapacity(UUID queueId) {
        UUID userId = requireUserId();
        Queue queue = findVisible(queueId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forTenant(queue.getTenantId()));
        long waiting = waitingCallRepository
                .countByQueueIdAndTenantIdAndStatusAndDeletedAtIsNull(
                        queue.getId(), queue.getTenantId(), QueueWaitingCallStatus.WAITING);
        long remaining = Math.max(0, queue.getMaxWaitingCalls() - waiting);
        return ResponseFactory.ok(new QueueCapacityResponse(
                queue.getId(), queue.getMaxWaitingCalls(), waiting, remaining));
    }

    // === membership ===

    /**
     * Adds an agent to a queue. Idempotent: an existing INACTIVE
     * membership is reactivated, an existing ACTIVE membership is
     * returned unchanged. Concurrent duplicate adds are collapsed by the
     * partial unique index; the loser retries into the idempotent path.
     * Cross-tenant agents fail closed (404). Membership operations never
     * mutate the agent itself.
     */
    @Transactional
    public ApiResponse<QueueMemberResponse> addMember(
            UUID queueId, AddQueueMemberRequest request) {
        UUID userId = requireUserId();
        Queue queue = findVisible(queueId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(queue.getTenantId()));

        Agent agent = agentRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(request.agentId(), queue.getTenantId())
                .orElseThrow(QueueDirectoryService::notFound);

        QueueMembership membership = membershipRepository
                .findByQueueIdAndAgentIdAndDeletedAtIsNull(queue.getId(), agent.getId())
                .orElseGet(() -> {
                    // Serialize concurrent adds per (queue, agent) with a
                    // transaction-scoped advisory lock — the proven VB-0/VB-3
                    // PostgreSQL pattern. The BLOCKING variant is required
                    // here (unlike VB-3's try-lock, where refusal is the
                    // correct outcome): a duplicate add must succeed
                    // idempotently, so the loser waits for the winner's
                    // transaction, then re-reads under the lock and finds
                    // the committed row. The lock is released automatically
                    // at commit/rollback; the partial unique index remains
                    // the last-resort guarantee.
                    long lockId = membershipLockId(queue.getId(), agent.getId());
                    entityManager.createNativeQuery(
                            "SELECT pg_advisory_xact_lock(CAST(:lockId AS bigint))")
                            .setParameter("lockId", lockId)
                            .getSingleResult();
                    // Re-read under the lock: a concurrent add may have
                    // committed between our first lookup and lock acquire.
                    return membershipRepository
                            .findByQueueIdAndAgentIdAndDeletedAtIsNull(
                                    queue.getId(), agent.getId())
                            .orElseGet(() -> {
                                QueueMembership m = new QueueMembership();
                                m.setQueueId(queue.getId());
                                m.setAgentId(agent.getId());
                                m.setTenantId(queue.getTenantId());
                                return membershipRepository.saveAndFlush(m);
                            });
                });
        if (membership.getStatus() != QueueMemberStatus.ACTIVE) {
            membership.setStatus(QueueMemberStatus.ACTIVE);
        }
        log.info("Queue member added (queue={}, agent={}, tenant={})",
                queue.getId(), agent.getId(), queue.getTenantId());
        return ResponseFactory.ok(toMemberResponse(membership));
    }

    /** Lists a queue's memberships (all statuses), tenant-scoped. */
    @Transactional(readOnly = true)
    public ApiResponse<List<QueueMemberResponse>> listMembers(UUID queueId) {
        UUID userId = requireUserId();
        Queue queue = findVisible(queueId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forTenant(queue.getTenantId()));
        List<QueueMembership> memberships =
                membershipRepository.findByQueueIdAndTenantIdAndDeletedAtIsNull(
                        queue.getId(), queue.getTenantId());
        return ResponseFactory.ok(memberships.stream()
                .map(this::toMemberResponse).toList());
    }

    /** Lists the queues an agent belongs to (all membership statuses). */
    @Transactional(readOnly = true)
    public ApiResponse<List<QueueMemberResponse>> listMembershipsForAgent(UUID agentId) {
        UUID userId = requireUserId();
        Agent agent = findAgentVisible(agentId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forTenant(agent.getTenantId()));
        List<QueueMembership> memberships =
                membershipRepository.findByAgentIdAndTenantIdAndDeletedAtIsNull(
                        agent.getId(), agent.getTenantId());
        return ResponseFactory.ok(memberships.stream()
                .map(this::toMemberResponse).toList());
    }

    /**
     * Updates membership status (ACTIVE/INACTIVE). Affects only the
     * queue↔agent relationship — never the agent's own state.
     */
    @Transactional
    public ApiResponse<QueueMemberResponse> updateMember(
            UUID queueId, UUID agentId, UpdateQueueMemberRequest request) {
        UUID userId = requireUserId();
        Queue queue = findVisible(queueId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(queue.getTenantId()));
        QueueMembership membership = findMemberVisible(queue, agentId);
        membership.setStatus(request.status());
        log.info("Queue member updated (queue={}, agent={}, status={})",
                queue.getId(), agentId, request.status());
        return ResponseFactory.ok(toMemberResponse(membership));
    }

    /**
     * Removes a membership (soft delete — established project pattern).
     * History is preserved; the agent can be re-added later. Never
     * touches the agent itself.
     */
    @Transactional
    public org.springframework.http.ResponseEntity<Void> removeMember(
            UUID queueId, UUID agentId) {
        UUID userId = requireUserId();
        Queue queue = findVisible(queueId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(queue.getTenantId()));
        QueueMembership membership = findMemberVisible(queue, agentId);
        String deletedBy = currentUserProvider.current().map(u -> u.email()).orElse("system");
        membership.setDeletedAt(Instant.now());
        membership.setDeletedBy(deletedBy);
        membershipRepository.save(membership);
        log.info("Queue member removed (queue={}, agent={}, tenant={})",
                queue.getId(), agentId, queue.getTenantId());
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    // === waiting calls (read model; entry/removal owned by later flows) ===

    /** Lists the queue's current WAITING calls in deterministic dispatch order. */
    @Transactional(readOnly = true)
    public ApiResponse<List<QueueWaitingCallResponse>> listWaitingCalls(UUID queueId) {
        UUID userId = requireUserId();
        Queue queue = findVisible(queueId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forTenant(queue.getTenantId()));
        List<QueueWaitingCall> waiting = waitingCallRepository
                .findByQueueIdAndTenantIdAndStatusAndDeletedAtIsNullOrderByEnteredAtAscIdAsc(
                        queue.getId(), queue.getTenantId(), QueueWaitingCallStatus.WAITING);
        return ResponseFactory.ok(waiting.stream().map(this::toWaitingResponse).toList());
    }

    // === helpers ===

    /**
     * Advisory-lock key space for queue membership — base 0x5, disjoint from
     * the VB-0 voice-capacity (0x1/0x2), VB-3 agent-reservation (0x3), and
     * VB-4D inbound-dedup (0x4) lock bases.
     */
    private static long membershipLockId(UUID queueId, UUID agentId) {
        return 0x500000000L + Math.abs(
                (queueId + ":" + agentId).hashCode()) % Integer.MAX_VALUE;
    }

    private QueueMembership findMemberVisible(Queue queue, UUID agentId) {
        return membershipRepository
                .findByQueueIdAndAgentIdAndDeletedAtIsNull(queue.getId(), agentId)
                .orElseThrow(QueueDirectoryService::notFound);
    }

    /**
     * Overflow configuration validation: an enabled overflow requires a
     * target queue that exists in the same tenant, is not DISABLED, and
     * is not the queue itself (self-overflow is meaningless; the DB check
     * constraint mirrors this). Disabling overflow clears the target.
     */
    private void applyOverflow(Queue queue, boolean enabled, UUID targetId, UUID tenantId) {
        if (!enabled) {
            queue.setOverflowEnabled(false);
            queue.setOverflowQueueId(null);
            return;
        }
        if (targetId == null) {
            throw business("overflowQueueId is required when overflow is enabled.");
        }
        if (targetId.equals(queue.getId())) {
            throw business("A queue cannot overflow to itself.");
        }
        Queue target = queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(targetId, tenantId)
                .orElseThrow(QueueDirectoryService::notFound);
        if (target.getStatus() == QueueStatus.DISABLED) {
            throw business("Overflow target queue is DISABLED.");
        }
        queue.setOverflowEnabled(true);
        queue.setOverflowQueueId(target.getId());
    }

    /** Agent lookup mirroring {@link #findVisible} boundary semantics. */
    private Agent findAgentVisible(UUID agentId, Scope scope) {
        if (scope.tenantId() != null) {
            return agentRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(agentId, scope.tenantId())
                    .orElseThrow(QueueDirectoryService::notFound);
        }
        Agent agent = agentRepository.findByIdAndDeletedAtIsNull(agentId)
                .orElseThrow(QueueDirectoryService::notFound);
        if (scope.resellerId() != null
                && !hierarchyTenantIds(scope.resellerId()).contains(agent.getTenantId())) {
            throw notFound();
        }
        return agent;
    }

    private Queue findVisible(UUID queueId, Scope scope) {
        if (scope.tenantId() != null) {
            return queueRepository.findByIdAndTenantIdAndDeletedAtIsNull(queueId, scope.tenantId())
                    .orElseThrow(QueueDirectoryService::notFound);
        }
        Queue queue = queueRepository.findByIdAndDeletedAtIsNull(queueId)
                .orElseThrow(QueueDirectoryService::notFound);
        if (scope.resellerId() != null
                && !hierarchyTenantIds(scope.resellerId()).contains(queue.getTenantId())) {
            throw notFound();
        }
        return queue;
    }

    private List<UUID> hierarchyTenantIds(UUID resellerId) {
        return tenantRepository.findAllByResellerIdAndStatus(
                resellerId, LifecycleStatus.ACTIVE).stream()
                .map(com.shivang.obd.tenant.TenantEntity::getId)
                .toList();
    }

    private void requireTenantUsable(UUID tenantId) {
        com.shivang.obd.tenant.TenantEntity tenant =
                tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                        .orElseThrow(QueueDirectoryService::notFound);
        if (tenant.getStatus() != LifecycleStatus.ACTIVE) {
            throw business("Referenced tenant does not exist or is not usable.");
        }
    }

    private QueueResponse toResponse(Queue queue) {
        return new QueueResponse(queue.getId(), queue.getTenantId(), queue.getName(),
                queue.getDescription(), queue.getStatus(), queue.getMaxWaitingCalls(),
                queue.getMaxWaitSeconds(), queue.isOverflowEnabled(),
                queue.getOverflowQueueId(), queue.getCreatedAt(), queue.getUpdatedAt());
    }

    private QueueMemberResponse toMemberResponse(QueueMembership membership) {
        return new QueueMemberResponse(membership.getId(), membership.getQueueId(),
                membership.getAgentId(), membership.getTenantId(), membership.getStatus(),
                membership.getCreatedAt(), membership.getUpdatedAt());
    }

    private QueueWaitingCallResponse toWaitingResponse(QueueWaitingCall waitingCall) {
        return new QueueWaitingCallResponse(waitingCall.getId(), waitingCall.getQueueId(),
                waitingCall.getCallSessionId(), waitingCall.getStatus(),
                waitingCall.getEnteredAt(), waitingCall.getExpiresAt());
    }

    private UUID requireUserId() {
        return currentUserProvider.current()
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.UNAUTHORIZED, "Authentication required."))
                .userId();
    }

    private Scope currentScope() {
        return Scope.of(OrganizationContextHolder.current().orElse(null));
    }

    private UUID requireTenant(Scope scope) {
        UUID tenantId = scope.tenantId();
        if (tenantId == null) {
            throw business("A tenant must be specified for this operation.");
        }
        return tenantId;
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Queue not found");
    }

    private static BusinessException business(String message) {
        return new BusinessException(CommonErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    private record Scope(UUID tenantId, UUID resellerId) {

        static Scope of(com.shivang.obd.authz.context.OrganizationContext ctx) {
            return ctx == null ? new Scope(null, null)
                    : new Scope(ctx.tenantId(), ctx.resellerId());
        }
    }

    private Pageable buildPageable(
            int page, int size, String[] sortArr, Sort defaultSort,
            List<String> sortableFields) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Sort sort = defaultSort;
        if (sortArr != null && sortArr.length >= 2) {
            String field = sortArr[0];
            Sort.Direction dir = "asc".equalsIgnoreCase(sortArr[1])
                    ? Sort.Direction.ASC : Sort.Direction.DESC;
            if (sortableFields.contains(field)) {
                sort = Sort.by(dir, field);
            }
        }
        return PageRequest.of(Math.max(page, 0), safeSize, sort);
    }
}
