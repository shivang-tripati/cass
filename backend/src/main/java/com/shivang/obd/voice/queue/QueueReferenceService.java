package com.shivang.obd.voice.queue;

import com.shivang.obd.voice.agent.AgentQueueReferenceChecker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link AgentQueueReferenceChecker} implementation, backed by the existing
 * VB-4B queue domain (VB-7A).
 *
 * <p>This class is the <em>only</em> reason the {@code campaign} module can see
 * a queue at all. It exists so that queue ownership and administrative lifecycle
 * stay answered by {@code Queue} and {@code QueueStatus} — VB-4B's authority —
 * instead of being re-derived by a campaign-side check that would inevitably
 * drift from it.
 *
 * <p>Every lookup is constrained by the caller's tenant, so a foreign queue is
 * reported exactly like a nonexistent one.
 */
@Service
@RequiredArgsConstructor
public class QueueReferenceService implements AgentQueueReferenceChecker {

    private final QueueRepository queueRepository;

    @Override
    public QueueUsability usabilityOf(UUID queueId, UUID tenantId) {
        if (queueId == null || tenantId == null) {
            return QueueUsability.NOT_ACCESSIBLE;
        }
        Queue queue = queueRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(queueId, tenantId)
                .orElse(null);
        if (queue == null) {
            // Missing, soft-deleted and foreign-tenant queues are deliberately
            // indistinguishable: a campaign configuration must not be usable to
            // discover another tenant's queue inventory.
            return QueueUsability.NOT_ACCESSIBLE;
        }
        return queue.getStatus() == QueueStatus.ACTIVE
                ? QueueUsability.USABLE
                : QueueUsability.NOT_ACTIVE;
    }
}
