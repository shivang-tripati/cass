package com.shivang.obd.voice.inbound;

import com.shivang.obd.voice.acd.AcdResult;
import com.shivang.obd.voice.acd.AcdService;
import com.shivang.obd.voice.queue.QueueWaitingCall;
import com.shivang.obd.voice.queue.QueueWaitingCallRepository;
import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACD retry loop for inbound queue calls (VB-4D).
 *
 * <p>VB-4C assigns a waiting call to an agent but performs no telephony;
 * VB-4D is the consumer: WAITING calls with a live caller are processed
 * FIFO ({@code enteredAt, id} — the VB-4B dispatch ordering) through the
 * existing {@link AcdService#attemptAssignment}. A successful assignment
 * is immediately connected via {@link InboundCallService#connectAssignedAgent}
 * (agent leg + originate); the shared VB-3 boundary owns answer/bridge/
 * hangup from there.</p>
 *
 * <p><b>No eligible agent</b> is a normal, non-terminal outcome: the
 * waiting call stays WAITING (VB-4C §32) and the next sweep retries —
 * no busy loops, no extra scheduler framework (@Scheduled convention).</p>
 *
 * <p><b>Bounded per sweep:</b> a fixed batch keeps a single pass
 * deterministic and short; queue timeout/overflow/expiry remain owned by
 * the VB-4C maintenance sweep.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboundAcdRetryScheduler {

    /** Maximum waiting calls processed per sweep. */
    private static final int BATCH_LIMIT = 25;

    private final QueueWaitingCallRepository waitingCallRepository;
    private final AcdService acdService;
    private final InboundCallService inboundCallService;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void sweep() {
        List<QueueWaitingCall> batch = waitingCalls().stream()
                .limit(BATCH_LIMIT)
                .toList();
        for (QueueWaitingCall waitingCall : batch) {
            try {
                processOne(waitingCall);
            } catch (RuntimeException e) {
                // One poisoned call must not block the rest of the batch.
                log.warn("ACD retry failed for waitingCall {}: {}",
                        waitingCall.getId(), e.getMessage());
            }
        }
        if (!batch.isEmpty()) {
            log.debug("Inbound ACD retry sweep processed {} waiting call(s)", batch.size());
        }
    }

    private List<QueueWaitingCall> waitingCalls() {
        return waitingCallRepository
                .findByStatusAndDeletedAtIsNullOrderByEnteredAtAscIdAsc(
                        QueueWaitingCallStatus.WAITING);
    }

    private void processOne(QueueWaitingCall waitingCall) {
        UUID tenantId = waitingCall.getTenantId();
        UUID queueId = waitingCall.getQueueId();
        UUID waitingCallId = waitingCall.getId();

        AcdResult result = acdService.attemptAssignment(tenantId, queueId, waitingCallId);
        if (result.status() == AcdResult.AcdStatus.ASSIGNED) {
            // Reservation exists — connect through the shared boundary.
            boolean connected = inboundCallService.connectAssignedAgent(waitingCallId);
            log.info("ACD assigned waiting call {} to agent {} (connected={})",
                    waitingCallId, result.agentId(), connected);
        } else if (result.status() == AcdResult.AcdStatus.OVERFLOWED) {
            log.info("Waiting call {} overflowed (queue={})", waitingCallId, queueId);
        }
        // NO_ELIGIBLE_AGENT / QUEUE_NOT_ELIGIBLE / ALREADY_ASSIGNED / etc.:
        // leave WAITING for the next sweep (or the timeout sweep terminates).
    }

    /** Test/ops seam: process a single waiting call once. */
    public boolean processOnce(UUID waitingCallId) {
        Optional<QueueWaitingCall> waitingCall =
                waitingCallRepository.findByIdAndDeletedAtIsNull(waitingCallId);
        if (waitingCall.isEmpty()
                || waitingCall.get().getStatus() != QueueWaitingCallStatus.WAITING) {
            return false;
        }
        processOne(waitingCall.get());
        return true;
    }
}
