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
 * Membership connecting a {@link Queue} to an {@code Agent}
 * (VB-4B Queue Foundation).
 *
 * <p>The same agent may belong to many queues; a membership is
 * independently manageable from the agent itself — an ACTIVE agent with
 * an INACTIVE membership is not part of the queue for routing purposes.
 * Removal is soft (established project deletion pattern); the partial
 * unique index {@code uq_queue_memberships_queue_agent} on
 * {@code (queue_id, agent_id) WHERE deleted_at IS NULL} is the
 * race-safety mechanism for concurrent add-member requests.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "queue_memberships", indexes = {
    @Index(name = "idx_queue_memberships_queue", columnList = "queue_id,deleted_at"),
    @Index(name = "idx_queue_memberships_agent", columnList = "agent_id,tenant_id,deleted_at"),
    @Index(name = "idx_queue_memberships_tenant", columnList = "tenant_id,deleted_at")
})
public class QueueMembership extends AuditableEntity {

    /** Owning tenant (fail-closed scoping on every lookup). */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The queue this membership belongs to. */
    @Column(name = "queue_id", nullable = false)
    private UUID queueId;

    /** The member agent (same tenant as the queue — service-enforced). */
    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** Membership lifecycle, independent of the agent's own state. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private QueueMemberStatus status = QueueMemberStatus.ACTIVE;
}
