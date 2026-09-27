package com.shivang.obd.voice.agent;

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
import com.shivang.obd.voice.agent.dto.AgentAvailabilityResponse;
import com.shivang.obd.voice.agent.dto.AgentEndpointResponse;
import com.shivang.obd.voice.agent.dto.AgentResponse;
import com.shivang.obd.voice.agent.dto.CreateAgentEndpointRequest;
import com.shivang.obd.voice.agent.dto.CreateAgentRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentEndpointRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentPresenceRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentRequest;
import com.shivang.obd.voice.agent.dto.UpdateAgentStatusRequest;
import com.shivang.obd.voice.call.CallLegRepository;
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
 * VB-4A Agent Foundation application service.
 *
 * <p>Agent lifecycle (create/get/list/update/administrative-status),
 * runtime presence (controlled transitions), the deterministic
 * availability query, and agent endpoint management. All operations are
 * tenant-scoped and capability-checked at this boundary
 * ({@code AGENT_VIEW}/{@code AGENT_MANAGE} — seeded in V1).</p>
 *
 * <p>Out of scope (deliberately): queues, ACD assignment, inbound/outbound
 * agent calling, WebRTC/Android endpoints. VB-4B+ build on this
 * foundation.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentDirectoryService {

    private static final String CAP_VIEW = "AGENT_VIEW";
    private static final String CAP_MANAGE = "AGENT_MANAGE";

    private static final List<String> AGENT_SORTABLE_FIELDS =
            List.of("displayName", "createdAt", "updatedAt");
    private static final Sort DEFAULT_AGENT_SORT =
            Sort.by(Sort.Direction.ASC, "displayName");
    private static final int MAX_PAGE_SIZE = 100;

    private final AgentRepository agentRepository;
    private final AgentEndpointRepository endpointRepository;
    private final AgentReservationRepository reservationRepository;
    private final CallLegRepository callLegRepository;
    private final TenantRepository tenantRepository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;

    // === agent lifecycle ===

    /** Creates an agent in the caller's tenant. New agents start DISABLED-presence (OFFLINE) but ACTIVE administratively. */
    @Transactional
    public ApiResponse<AgentResponse> createAgent(CreateAgentRequest request) {
        UUID userId = requireUserId();
        Scope scope = currentScope();
        UUID tenantId = requireTenant(scope);
        requireTenantUsable(tenantId);
        authorizationService.requireCapability(userId, CAP_MANAGE, AccessCheck.forTenant(tenantId));

        Agent agent = new Agent();
        agent.setTenantId(tenantId);
        agent.setDisplayName(request.displayName().trim());
        agent.setMaxConcurrentCalls(
                request.maxConcurrentCalls() != null ? request.maxConcurrentCalls() : 1);
        agent.setUserId(request.userId());
        // defaults: adminStatus ACTIVE, availability OFFLINE (V36 defaults)
        Agent saved = agentRepository.save(agent);
        log.info("Agent created (agent={}, tenant={})", saved.getId(), tenantId);
        return ResponseFactory.created(toResponse(saved));
    }

    /** Tenant-scoped read; foreign and nonexistent agents are indistinguishable (404). */
    @Transactional(readOnly = true)
    public ApiResponse<AgentResponse> getAgent(UUID agentId) {
        UUID userId = requireUserId();
        Agent agent = findVisible(agentId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forTenant(agent.getTenantId()));
        return ResponseFactory.ok(toResponse(agent));
    }

    /** Paginated agent listing scoped to the caller's organizational boundary. */
    @Transactional(readOnly = true)
    public ApiResponse<List<AgentResponse>> listAgents(
            int page, int size, String[] sortArr, String search) {
        UUID userId = requireUserId();
        Scope scope = currentScope();

        Specification<Agent> boundary;
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

        Specification<Agent> spec = AgentSpecifications.compose(
                AgentSpecifications.notDeleted(), boundary,
                AgentSpecifications.search(search));

        Page<Agent> resultPage = agentRepository.findAll(
                spec, buildPageable(page, size, sortArr, DEFAULT_AGENT_SORT, AGENT_SORTABLE_FIELDS));
        List<AgentResponse> items = resultPage.getContent().stream()
                .map(this::toResponse).toList();
        return ResponseFactory.page(items,
                PaginationMetadata.of(page, size, resultPage.getTotalElements()));
    }

    /** Updates mutable agent information (name, concurrency budget, identity link). */
    @Transactional
    public ApiResponse<AgentResponse> updateAgent(UUID agentId, UpdateAgentRequest request) {
        UUID userId = requireUserId();
        Agent agent = findVisible(agentId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(agent.getTenantId()));

        if (request.displayName() != null) {
            agent.setDisplayName(request.displayName().trim());
        }
        if (request.maxConcurrentCalls() != null) {
            if (request.maxConcurrentCalls() < 1) {
                throw business("maxConcurrentCalls must be at least 1.");
            }
            agent.setMaxConcurrentCalls(request.maxConcurrentCalls());
        }
        if (request.userId() != null) {
            agent.setUserId(request.userId());
        }
        log.info("Agent updated (agent={}, tenant={})", agentId, agent.getTenantId());
        return ResponseFactory.ok(toResponse(agent));
    }

    /**
     * Administrative lifecycle transition. Valid transitions:
     * ACTIVE→SUSPENDED, SUSPENDED→ACTIVE, ACTIVE|SUSPENDED→DISABLED.
     * DISABLED is terminal. Setting the current status is idempotent.
     */
    @Transactional
    public ApiResponse<AgentResponse> updateStatus(UUID agentId, UpdateAgentStatusRequest request) {
        UUID userId = requireUserId();
        Agent agent = findVisible(agentId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(agent.getTenantId()));

        AgentAdminStatus target = request.adminStatus();
        if (target == agent.getAdminStatus()) {
            return ResponseFactory.ok(toResponse(agent)); // idempotent
        }
        if (agent.getAdminStatus() == AgentAdminStatus.DISABLED) {
            throw new ConflictException(
                    "Agent is DISABLED; DISABLED is a terminal administrative status.");
        }
        if (target == AgentAdminStatus.DISABLED
                || (agent.getAdminStatus() == AgentAdminStatus.ACTIVE
                    && target == AgentAdminStatus.SUSPENDED)
                || (agent.getAdminStatus() == AgentAdminStatus.SUSPENDED
                    && target == AgentAdminStatus.ACTIVE)) {
            agent.setAdminStatus(target);
            // A suspended/disabled agent is by definition not available now.
            if (target != AgentAdminStatus.ACTIVE
                    && agent.getAvailability() == AgentAvailability.AVAILABLE) {
                agent.setAvailability(AgentAvailability.OFFLINE);
            }
            log.info("Agent status changed (agent={}, tenant={}, status={})",
                    agentId, agent.getTenantId(), target);
            return ResponseFactory.ok(toResponse(agent));
        }
        throw business("Invalid administrative status transition: "
                + agent.getAdminStatus() + " -> " + target + ".");
    }

    // === presence ===

    /**
     * Runtime presence transition. Only agent-declared states are accepted:
     * AVAILABLE and OFFLINE. BUSY is owned by the reservation lifecycle
     * (VB-3) and cannot be set through this API. Suspended/disabled agents
     * cannot declare themselves available. Same-state updates are idempotent.
     */
    @Transactional
    public ApiResponse<AgentResponse> updatePresence(
            UUID agentId, UpdateAgentPresenceRequest request) {
        UUID userId = requireUserId();
        Agent agent = findVisible(agentId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(agent.getTenantId()));

        AgentAvailability target = request.availability();
        if (target == AgentAvailability.BUSY) {
            throw business(
                    "BUSY is owned by the call lifecycle and cannot be set manually.");
        }
        if (target == agent.getAvailability()) {
            return ResponseFactory.ok(toResponse(agent)); // idempotent
        }
        if (agent.getAdminStatus() != AgentAdminStatus.ACTIVE) {
            throw business("Agent is " + agent.getAdminStatus()
                    + "; presence changes require an ACTIVE agent.");
        }
        agent.setAvailability(target);
        log.info("Agent presence changed (agent={}, tenant={}, presence={})",
                agentId, agent.getTenantId(), target);
        return ResponseFactory.ok(toResponse(agent));
    }

    // === availability (foundation query for the future ACD layer) ===

    /**
     * Deterministic, explainable current availability:
     * administrative status + runtime presence + endpoint readiness
     * + live agent-leg count vs the concurrency budget. Derived entirely
     * from canonical data (Agent + AgentEndpoint + CallLeg) — no cached
     * counters, no second source of truth.
     */
    @Transactional(readOnly = true)
    public ApiResponse<AgentAvailabilityResponse> getAvailability(UUID agentId) {
        UUID userId = requireUserId();
        Agent agent = findVisible(agentId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forTenant(agent.getTenantId()));
        return ResponseFactory.ok(availabilityOf(agent));
    }

    private AgentAvailabilityResponse availabilityOf(Agent agent) {
        UUID agentId = agent.getId();
        if (agent.getAdminStatus() == AgentAdminStatus.DISABLED) {
            return unavailable(agent, AgentFoundationReasons.AGENT_DISABLED);
        }
        if (agent.getAdminStatus() == AgentAdminStatus.SUSPENDED) {
            return unavailable(agent, AgentFoundationReasons.AGENT_SUSPENDED);
        }
        if (agent.getAvailability() == AgentAvailability.OFFLINE) {
            return unavailable(agent, AgentFoundationReasons.AGENT_OFFLINE);
        }
        boolean hasEndpoint = !endpointRepository
                .findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                        agentId, agent.getTenantId())
                .isEmpty();
        if (!hasEndpoint) {
            return unavailable(agent, AgentFoundationReasons.AGENT_ENDPOINT_INVALID);
        }
        int activeCalls = callLegRepository
                .countByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                        agentId, com.shivang.obd.voice.call.CallLegType.AGENT,
                        AgentSpecifications.ACTIVE_LEG_STATUSES);
        if (activeCalls >= agent.getMaxConcurrentCalls()) {
            return unavailable(agent, AgentFoundationReasons.AGENT_AT_CAPACITY);
        }
        return new AgentAvailabilityResponse(agentId, agent.getTenantId(),
                agent.getAdminStatus(), agent.getAvailability(), true,
                AgentFoundationReasons.AVAILABLE);
    }

    private AgentAvailabilityResponse unavailable(Agent agent, String reason) {
        return new AgentAvailabilityResponse(agent.getId(), agent.getTenantId(),
                agent.getAdminStatus(), agent.getAvailability(), false, reason);
    }

    // === endpoint management ===

    /** Creates an endpoint for an agent. Ownership: agent's tenant; types limited to the dialable set. */
    @Transactional
    public ApiResponse<AgentEndpointResponse> createEndpoint(
            UUID agentId, CreateAgentEndpointRequest request) {
        UUID userId = requireUserId();
        Agent agent = findVisible(agentId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(agent.getTenantId()));

        if (request.endpointType() != com.shivang.obd.voice.call.EndpointType.SIP
                && request.endpointType() != com.shivang.obd.voice.call.EndpointType.EXTERNAL_FORWARD) {
            throw business("Endpoint type " + request.endpointType()
                    + " is not supported; supported types: SIP, EXTERNAL_FORWARD.");
        }
        String target = request.dialTarget().trim();
        validateDialTarget(request.endpointType(), target);

        AgentEndpointEntity endpoint = new AgentEndpointEntity();
        endpoint.setAgentId(agent.getId());
        endpoint.setTenantId(agent.getTenantId());
        endpoint.setEndpointType(request.endpointType());
        endpoint.setDialTarget(target);
        endpoint.setEnabled(true);
        AgentEndpointEntity saved = endpointRepository.save(endpoint);
        log.info("Agent endpoint created (endpoint={}, agent={}, tenant={})",
                saved.getId(), agentId, agent.getTenantId());
        return ResponseFactory.created(toResponse(saved));
    }

    /** Tenant-scoped endpoint read. */
    @Transactional(readOnly = true)
    public ApiResponse<AgentEndpointResponse> getEndpoint(UUID endpointId) {
        UUID userId = requireUserId();
        AgentEndpointEntity endpoint = findEndpointVisible(endpointId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forTenant(endpoint.getTenantId()));
        return ResponseFactory.ok(toResponse(endpoint));
    }

    /** Lists an agent's endpoints (all enabled states), tenant-scoped. */
    @Transactional(readOnly = true)
    public ApiResponse<List<AgentEndpointResponse>> listEndpoints(UUID agentId) {
        UUID userId = requireUserId();
        Agent agent = findVisible(agentId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_VIEW, AccessCheck.forTenant(agent.getTenantId()));
        List<AgentEndpointEntity> endpoints =
                endpointRepository.findByAgentIdAndTenantIdAndDeletedAtIsNull(
                        agent.getId(), agent.getTenantId());
        return ResponseFactory.ok(endpoints.stream().map(this::toResponse).toList());
    }

    /** Updates an endpoint's dial target (type is immutable). */
    @Transactional
    public ApiResponse<AgentEndpointResponse> updateEndpoint(
            UUID endpointId, UpdateAgentEndpointRequest request) {
        UUID userId = requireUserId();
        AgentEndpointEntity endpoint = findEndpointVisible(endpointId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(endpoint.getTenantId()));
        String target = request.dialTarget().trim();
        validateDialTarget(endpoint.getEndpointType(), target);
        endpoint.setDialTarget(target);
        log.info("Agent endpoint updated (endpoint={}, tenant={})",
                endpointId, endpoint.getTenantId());
        return ResponseFactory.ok(toResponse(endpoint));
    }

    /** Enables an endpoint. Idempotent when already enabled. */
    @Transactional
    public ApiResponse<AgentEndpointResponse> enableEndpoint(UUID endpointId) {
        return setEndpointEnabled(endpointId, true);
    }

    /** Disables an endpoint. Idempotent when already disabled. */
    @Transactional
    public ApiResponse<AgentEndpointResponse> disableEndpoint(UUID endpointId) {
        return setEndpointEnabled(endpointId, false);
    }

    private ApiResponse<AgentEndpointResponse> setEndpointEnabled(
            UUID endpointId, boolean enabled) {
        UUID userId = requireUserId();
        AgentEndpointEntity endpoint = findEndpointVisible(endpointId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(endpoint.getTenantId()));
        if (endpoint.isEnabled() != enabled) {
            endpoint.setEnabled(enabled);
            log.info("Agent endpoint {} (endpoint={}, tenant={})",
                    enabled ? "enabled" : "disabled", endpointId, endpoint.getTenantId());
        }
        return ResponseFactory.ok(toResponse(endpoint));
    }

    /** Soft-deletes an endpoint (established project deletion pattern). */
    @Transactional
    public org.springframework.http.ResponseEntity<Void> deactivateEndpoint(UUID endpointId) {
        UUID userId = requireUserId();
        AgentEndpointEntity endpoint = findEndpointVisible(endpointId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(endpoint.getTenantId()));
        String deletedBy = currentUserProvider.current().map(u -> u.email()).orElse("system");
        endpoint.setDeletedAt(java.time.Instant.now());
        endpoint.setDeletedBy(deletedBy);
        endpointRepository.save(endpoint);
        log.info("Agent endpoint deactivated (endpoint={}, tenant={})",
                endpointId, endpoint.getTenantId());
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    // === helpers ===

    private void validateDialTarget(
            com.shivang.obd.voice.call.EndpointType type, String target) {
        if (type == com.shivang.obd.voice.call.EndpointType.EXTERNAL_FORWARD
                && !target.matches("^\\+[1-9][0-9]{4,19}$")) {
            throw business("EXTERNAL_FORWARD dial target must be an E.164 number.");
        }
        if (type == com.shivang.obd.voice.call.EndpointType.SIP
                && !target.matches("^[^@\\s]+@[^@\\s]+$")) {
            throw business("SIP dial target must be a SIP address (user@host).");
        }
    }

    private Agent findVisible(UUID agentId, Scope scope) {
        if (scope.tenantId() != null) {
            return agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agentId, scope.tenantId())
                    .orElseThrow(AgentDirectoryService::notFound);
        }
        Agent agent = agentRepository.findByIdAndDeletedAtIsNull(agentId)
                .orElseThrow(AgentDirectoryService::notFound);
        if (scope.resellerId() != null
                && !hierarchyTenantIds(scope.resellerId()).contains(agent.getTenantId())) {
            throw notFound();
        }
        return agent;
    }

    private AgentEndpointEntity findEndpointVisible(UUID endpointId, Scope scope) {
        if (scope.tenantId() != null) {
            return endpointRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(endpointId, scope.tenantId())
                    .orElseThrow(AgentDirectoryService::notFound);
        }
        AgentEndpointEntity endpoint = endpointRepository.findById(endpointId)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(AgentDirectoryService::notFound);
        if (scope.resellerId() != null
                && !hierarchyTenantIds(scope.resellerId()).contains(endpoint.getTenantId())) {
            throw notFound();
        }
        return endpoint;
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
                        .orElseThrow(AgentDirectoryService::notFound);
        if (tenant.getStatus() != LifecycleStatus.ACTIVE) {
            throw business("Referenced tenant does not exist or is not usable.");
        }
    }

    private AgentResponse toResponse(Agent agent) {
        int activeCalls = callLegRepository
                .countByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                        agent.getId(), com.shivang.obd.voice.call.CallLegType.AGENT,
                        AgentSpecifications.ACTIVE_LEG_STATUSES);
        return new AgentResponse(agent.getId(), agent.getTenantId(),
                agent.getDisplayName(), agent.getAdminStatus(), agent.getAvailability(),
                agent.getMaxConcurrentCalls(), agent.getUserId(), activeCalls,
                agent.getCreatedAt(), agent.getUpdatedAt());
    }

    private AgentEndpointResponse toResponse(AgentEndpointEntity endpoint) {
        return new AgentEndpointResponse(endpoint.getId(), endpoint.getAgentId(),
                endpoint.getTenantId(), endpoint.getEndpointType(), endpoint.getDialTarget(),
                endpoint.isEnabled(), endpoint.getCreatedAt(), endpoint.getUpdatedAt());
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
        return new ResourceNotFoundException("Agent not found");
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
