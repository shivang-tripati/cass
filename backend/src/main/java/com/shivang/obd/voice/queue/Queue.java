package com.shivang.obd.voice.queue;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A tenant-owned queue (VB-4B Queue Foundation).
 *
 * <p>Represents "which agents belong to this queue" via
 * {@link QueueMembership} rows. Waiting-call capacity, wait timeout and
 * overflow are persisted <em>configuration only</em> in VB-4B — the
 * execution of timeouts/overflow and any agent selection belong to
 * VB-4C (ACD) and later phases.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "queues", indexes = {
    @Index(name = "idx_queues_tenant", columnList = "tenant_id,deleted_at"),
    @Index(name = "idx_queues_status", columnList = "tenant_id,status,deleted_at")
})
public class Queue extends AuditableEntity {

    /** Owning tenant — queues are never shared across tenants. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Human-readable queue name (unique per tenant among live queues). */
    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Optional operational description. */
    @Column(name = "description", length = 500)
    private String description;

    /** Administrative lifecycle — ACTIVE | INACTIVE | DISABLED (terminal). */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private QueueStatus status = QueueStatus.ACTIVE;

    /** Configured waiting capacity (>= 0). Configuration only in VB-4B. */
    @Column(name = "max_waiting_calls", nullable = false)
    private int maxWaitingCalls = 100;

    /** Configured wait-time budget in seconds (>= 0). Configuration only in VB-4B. */
    @Column(name = "max_wait_seconds", nullable = false)
    private int maxWaitSeconds = 300;

    /** Whether overflow is configured. Execution belongs to later phases. */
    @Column(name = "overflow_enabled", nullable = false)
    private boolean overflowEnabled = false;

    /**
     * Overflow target queue. Same tenant only, never this queue itself
     * (DB check + service validation). Configuration only in VB-4B.
     */
    @Column(name = "overflow_queue_id")
    private UUID overflowQueueId;

    /** Whether this queue can participate in future queue operations. */
    public boolean isOperationallyActive() {
        return status == QueueStatus.ACTIVE;
    }
}
