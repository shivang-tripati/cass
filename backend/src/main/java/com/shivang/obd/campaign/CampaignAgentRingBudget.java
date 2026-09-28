package com.shivang.obd.campaign;

import com.shivang.obd.voice.agent.AgentRingBudgetResolver;
import com.shivang.obd.voice.agent.AgentRingWindow;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AgentRingBudgetResolver} implementation (VB-7A): a call session's ring
 * window comes from the execution's frozen snapshot, or from the platform
 * default when the call has no CONNECT_BY_AGENT configuration.
 *
 * <p>Read-only, and it never falls back to the live campaign. A ring budget that
 * a campaign edit could change mid-ring would be a budget nobody could rely on,
 * so the value is resolved from the same immutable snapshot the connection
 * service already reads for its queue.
 *
 * <p>Every unresolved case — a non-campaign call, a DTMF/IVR campaign whose
 * terminal action is {@code CONNECT_BY_AGENT}, a missing execution, a corrupted
 * snapshot — returns the supplied platform default. Enforcing a stale or
 * fabricated budget on a call already in progress would be strictly worse than
 * the constant this replaced.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignAgentRingBudget implements AgentRingBudgetResolver {

    private final CallSessionRepository callSessionRepository;
    private final CallAttemptRepository callAttemptRepository;
    private final CampaignExecutionRepository executionRepository;
    private final CampaignRuntimeConfigResolver runtimeConfigResolver;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public int ringSecondsFor(UUID callSessionId, int platformDefaultSeconds) {
        if (callSessionId == null) {
            return platformDefaultSeconds;
        }
        try {
            CallSession session = callSessionRepository
                    .findByIdAndDeletedAtIsNull(callSessionId).orElse(null);
            if (session == null || session.getCallAttemptId() == null) {
                return platformDefaultSeconds;
            }
            CallAttempt attempt = callAttemptRepository
                    .findByIdAndDeletedAtIsNull(session.getCallAttemptId()).orElse(null);
            if (attempt == null) {
                return platformDefaultSeconds;
            }
            CampaignExecution execution = executionRepository
                    .findByIdAndDeletedAtIsNull(attempt.getExecutionId()).orElse(null);
            if (execution == null) {
                return platformDefaultSeconds;
            }
            return runtimeConfigResolver.resolve(execution)
                    .asConnectByAgent()
                    .map(cba -> AgentRingWindow.effectiveSeconds(cba.ringDurationSeconds()))
                    .orElse(platformDefaultSeconds);
        } catch (RuntimeException e) {
            log.warn("Ring budget lookup failed for callSession {}: {} — using the platform "
                    + "default of {}s", callSessionId, e.getMessage(), platformDefaultSeconds);
            return platformDefaultSeconds;
        }
    }
}
