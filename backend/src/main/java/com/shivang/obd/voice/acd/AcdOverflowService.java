package com.shivang.obd.voice.acd;

import com.shivang.obd.voice.queue.Queue;
import com.shivang.obd.voice.queue.QueueRepository;
import com.shivang.obd.voice.queue.QueueWaitingCallRepository;
import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queue overflow execution (VB-4C) — moves WAITING calls from an
 * over-capacity queue to its configured overflow target.
 *
 * <p>Executes the VB-4B persisted configuration
 * ({@code overflow_enabled} + {@code overflow_queue_id}) under the
 * validation rules established there: same tenant, target operational
 * (not DISABLED), never the queue itself. The DB check constraint
 * {@code ck_queues_overflow_ref} mirrors the self-overflow rule.</p>
 *
 * <p><b>Bounded, no loops:</b> the conditional-move UPDATE matches the
 * SOURCE queue id only. Even if A→B and B→A are both configured, one
 * invocation moves each waiting row at most once; rows landing in B
 * wait for a future, explicit invocation against B. Recursion is
 * structurally impossible in a single operation.</p>
 *
 * <p><b>Idempotent:</b> the move matches WAITING rows only — already
 * moved/assigned/abandoned rows are untouched, so repeated invocations
 * converge.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AcdOverflowService {

    private final QueueRepository queueRepository;
    private final QueueWaitingCallRepository waitingCallRepository;

    /**
     * Moves waiting calls from {@code queueId} to its overflow target.
     *
     * @return number of waiting calls moved (0 when overflow is not
     *         configured, source/target invalid, or nothing to move)
     */
    @Transactional
    public int moveWaitingCallsToOverflow(UUID tenantId, UUID queueId) {
        Queue source = queueRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(queueId, tenantId)
                .orElse(null);
        if (source == null || !source.isOverflowEnabled()
                || source.getOverflowQueueId() == null) {
            return 0;
        }
        Queue target = queueRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(
                        source.getOverflowQueueId(), tenantId)
                .orElse(null);
        if (target == null || target.getStatus()
                != com.shivang.obd.voice.queue.QueueStatus.ACTIVE) {
            log.warn("Overflow target invalid for queue {} — skipping", queueId);
            return 0;
        }
        int moved = waitingCallRepository.moveWaitingCallsToQueue(
                source.getId(), target.getId(), tenantId);
        if (moved > 0) {
            log.info("Overflow executed (tenant={}, from={}, to={}, moved={})",
                    tenantId, source.getId(), target.getId(), moved);
        }
        return moved;
    }
}
