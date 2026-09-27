package com.shivang.obd.voice.queue;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistence for "this canonical {@code CallSession} is currently
 * waiting in this {@link Queue}" (VB-4B Queue Foundation).
 *
 * <p>Deliberately duplicates NO call attributes: phone number, direction,
 * provider UUIDs and call status live on {@code CallSession}/{@code CallLeg}
 * and are not snapshotted here. No agent leg is created by this row —
 * agent assignment belongs to ACD (VB-4C).</p>
 *
 * <p>{@code enteredAt} is authoritative for deterministic future
 * dispatch ordering ({@code ORDER BY entered_at, id}); {@code expiresAt}
 * persists the queue's configured wait budget at entry time. VB-4B
 * executes neither timeout nor overflow — this row is pure
 * representation.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "queue_waiting_calls", indexes = {
    @Index(name = "idx_queue_waiting_calls_dispatch",
        columnList = "queue_id,status,entered_at,id"),
    @Index(name = "idx_queue_waiting_calls_tenant", columnList = "tenant_id,deleted_at"),
    @Index(name = "idx_queue_waiting_calls_session", columnList = "call_session_id")
})
public class QueueWaitingCall extends AuditableEntity {

    /** Owning tenant. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The queue in which the call is waiting. */
    @Column(name = "queue_id", nullable = false)
    private UUID queueId;

    /** The canonical call session (FK) — no call attributes duplicated. */
    @Column(name = "call_session_id", nullable = false)
    private UUID callSessionId;

    /** Waiting representation lifecycle; WAITING is steady in VB-4B. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private QueueWaitingCallStatus status = QueueWaitingCallStatus.WAITING;

    /** Queue entry time — authoritative for deterministic ordering. */
    @Column(name = "entered_at", nullable = false)
    private Instant enteredAt = Instant.now();

    /** Persisted wait budget at entry (queue.maxWaitSeconds); informational — the queue-timeout sweep executes it. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Agent ACD reserved for this call (set atomically with the ASSIGNED claim). */
    @Column(name = "assigned_agent_id")
    private UUID assignedAgentId;

    /** The reservation backing this assignment (agent_reservations FK). */
    @Column(name = "assigned_reservation_id")
    private UUID assignedReservationId;

    /** When ACD claimed the assignment. */
    @Column(name = "assigned_at")
    private Instant assignedAt;
}
