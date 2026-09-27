package com.shivang.obd.voice.agent;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.PaginationMetadata;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.agent.dto.AgentActiveCallResponse;
import com.shivang.obd.voice.agent.dto.AgentCallHistoryResponse;
import com.shivang.obd.voice.call.CallDirection;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-4A agent call queries: "What calls is this agent currently involved
 * in?" and "Show this agent's call history".
 *
 * <p>Both derive from the canonical CallSession/CallLeg model — no
 * AgentActiveCall table, no second source of truth. An agent's calls are
 * its AGENT legs (plus EXTERNAL forwarding legs, which are agent legs in
 * the VB-3 model); the session provides tenant ownership, direction and
 * the remote target. Every access is tenant-scoped and capability-checked
 * (CALL_VIEW) at this boundary.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentCallQueryService {

    private static final String CAP_CALL_VIEW = "CALL_VIEW";
    private static final int MAX_PAGE_SIZE = 100;

    private final AgentRepository agentRepository;
    private final CallLegRepository callLegRepository;
    private final CallSessionRepository sessionRepository;
    private final TenantRepository tenantRepository;
    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * The agent's live calls. Legs in active statuses are returned;
     * sessions with ended_at are excluded (terminated sessions never
     * appear as active, regardless of leg state).
     */
    @Transactional(readOnly = true)
    public ApiResponse<List<AgentActiveCallResponse>> getActiveCalls(UUID agentId) {
        UUID userId = requireUserId();
        Agent agent = findVisible(agentId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_CALL_VIEW, AccessCheck.forTenant(agent.getTenantId()));

        List<CallLeg> legs = callLegRepository
                .findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullOrderByInitiatedAtDesc(
                        agentId, CallLegType.AGENT, AgentSpecifications.ACTIVE_LEG_STATUSES);
        List<AgentActiveCallResponse> items = legs.stream()
                .map(leg -> Map.entry(leg, sessionOf(leg)))
                .filter(e -> e.getValue() != null && e.getValue().getEndedAt() == null)
                .map(e -> toActiveResponse(e.getValue(), e.getKey()))
                .toList();
        return ResponseFactory.ok(items);
    }

    /**
     * The agent's historical (terminal) calls. A leg qualifies as
     * historical when its status is terminal AND its session has ended.
     * Supports pagination and an optional initiated-at date range.
     */
    @Transactional(readOnly = true)
    public ApiResponse<List<AgentCallHistoryResponse>> getCallHistory(
            UUID agentId, int page, int size, Instant from, Instant to) {
        UUID userId = requireUserId();
        Agent agent = findVisible(agentId, currentScope());
        authorizationService.requireCapability(
                userId, CAP_CALL_VIEW, AccessCheck.forTenant(agent.getTenantId()));

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<CallLeg> legPage;
        if (from != null && to != null) {
            legPage = callLegRepository
                    .findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullAndInitiatedAtGreaterThanEqualAndInitiatedAtLessThanEqual(
                            agentId, CallLegType.AGENT, AgentSpecifications.HISTORY_LEG_STATUSES,
                            from, to, pageable);
        } else if (from != null) {
            legPage = callLegRepository
                    .findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullAndInitiatedAtGreaterThanEqual(
                            agentId, CallLegType.AGENT, AgentSpecifications.HISTORY_LEG_STATUSES,
                            from, pageable);
        } else if (to != null) {
            legPage = callLegRepository
                    .findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullAndInitiatedAtLessThanEqual(
                            agentId, CallLegType.AGENT, AgentSpecifications.HISTORY_LEG_STATUSES,
                            to, pageable);
        } else {
            legPage = callLegRepository
                    .findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
                            agentId, CallLegType.AGENT, AgentSpecifications.HISTORY_LEG_STATUSES,
                            pageable);
        }

        Map<UUID, CallSession> sessions = legPage.getContent().stream()
                .map(CallLeg::getCallSessionId).distinct()
                .collect(Collectors.toMap(Function.identity(),
                        sid -> sessionRepository.findById(sid).orElse(null),
                        (a, b) -> a));
        List<AgentCallHistoryResponse> items = legPage.getContent().stream()
                .filter(l -> {
                    CallSession s = sessions.get(l.getCallSessionId());
                    return s != null && s.getEndedAt() != null; // only ended sessions are historical
                })
                .map(l -> toHistoryResponse(sessions.get(l.getCallSessionId()), l))
                .toList();
        // Filtered page may be smaller than the DB page; keep DB pagination metadata.
        return ResponseFactory.page(items, PaginationMetadata.of(
                safePage, safeSize, legPage.getTotalElements()));
    }

    // === helpers ===

    private CallSession sessionOf(CallLeg leg) {
        return sessionRepository.findById(leg.getCallSessionId()).orElse(null);
    }

    private AgentActiveCallResponse toActiveResponse(CallSession session, CallLeg leg) {
        return new AgentActiveCallResponse(
                session.getId(), leg.getId(),
                session.getStatus().name(), leg.getStatus().name(),
                leg.getLegType().name(),
                session.getDirection() != null ? session.getDirection().name() : null,
                session.getDestinationNumber(),
                leg.getProviderCallId(), leg.getInitiatedAt(), leg.getAnsweredAt());
    }

    private AgentCallHistoryResponse toHistoryResponse(CallSession session, CallLeg leg) {
        return new AgentCallHistoryResponse(
                session.getId(), leg.getId(),
                session.getStatus().name(), leg.getStatus().name(),
                leg.getLegType().name(),
                session.getDirection() != null ? session.getDirection().name() : null,
                session.getDestinationNumber(),
                leg.getFailureCode(), leg.getFailureReason(),
                leg.getInitiatedAt(), leg.getAnsweredAt(), leg.getEndedAt());
    }

    private Agent findVisible(UUID agentId, Scope scope) {
        if (scope.tenantId() != null) {
            return agentRepository.findByIdAndTenantIdAndDeletedAtIsNull(agentId, scope.tenantId())
                    .orElseThrow(AgentCallQueryService::notFound);
        }
        Agent agent = agentRepository.findByIdAndDeletedAtIsNull(agentId)
                .orElseThrow(AgentCallQueryService::notFound);
        if (scope.resellerId() != null
                && !tenantRepository.findAllByResellerIdAndStatus(
                        scope.resellerId(), LifecycleStatus.ACTIVE).stream()
                        .map(com.shivang.obd.tenant.TenantEntity::getId).toList()
                        .contains(agent.getTenantId())) {
            throw notFound();
        }
        return agent;
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
        return new ResourceNotFoundException("Agent not found");
    }

    private record Scope(UUID tenantId, UUID resellerId) {

        static Scope of(com.shivang.obd.authz.context.OrganizationContext ctx) {
            return ctx == null ? new Scope(null, null)
                    : new Scope(ctx.tenantId(), ctx.resellerId());
        }
    }
}
