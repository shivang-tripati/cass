package com.shivang.obd.voice.outbound;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.voice.agent.dto.AgentCallResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API boundary for agent-originated outbound calls (VB-4E).
 *
 * <p>Binds the platform's existing authorization model to the core
 * {@link AgentOutboundCallService}: the caller must be authenticated and
 * hold {@code AGENT_MANAGE} for the agent's tenant (the same capability
 * the agent foundation uses for administrative mutations — no new RBAC
 * concept is introduced). Tenant scoping is server-derived from the
 * organizational context; a foreign agent is indistinguishable from a
 * nonexistent one (404-style business error), so no cross-tenant
 * initiation is possible.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentOutboundApiService {

    /** Same capability the agent foundation uses for mutations (VB-4A). */
    private static final String CAP_MANAGE = "AGENT_MANAGE";

    private final AuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;
    private final AgentOutboundCallService outboundCallService;

    /**
     * Handles {@code POST /api/v1/agents/{agentId}/calls}.
     *
     * <p>Authorization: authenticated principal + AGENT_MANAGE on the
     * caller's tenant. The agent lookup inside the core service is
     * tenant-scoped, so a cross-tenant agent id fails closed.</p>
     */
    @Transactional
    public ApiResponse<AgentCallResponse> requestCall(UUID agentId, String destination) {
        UUID userId = currentUserProvider.current()
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.UNAUTHORIZED, "Authentication required."))
                .userId();

        var context = OrganizationContextHolder.current()
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.UNAUTHORIZED, "Organizational context required."));
        UUID tenantId = context.tenantId();
        if (tenantId == null) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "A tenant context is required to originate calls.");
        }

        authorizationService.requireCapability(
                userId, CAP_MANAGE, AccessCheck.forTenant(tenantId));

        try {
            AgentOutboundCallResult result =
                    outboundCallService.placeCall(tenantId, agentId, destination);
            log.info("Agent outbound call accepted (agent={}, callSession={})",
                    agentId, result.callSessionId());
            return ResponseFactory.created(new AgentCallResponse(
                    result.callSessionId(),
                    result.agentLegId(),
                    result.customerLegId(),
                    result.agentId(),
                    result.destinationNumber()));
        } catch (AgentOutboundCallException e) {
            // Deterministic, explainable rejection — surface the reason code
            // in the message body without leaking internals.
            throw new BusinessException(CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    e.getReasonCode() + ": " + e.getMessage());
        }
    }
}
