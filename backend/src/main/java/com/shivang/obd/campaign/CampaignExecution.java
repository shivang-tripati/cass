package com.shivang.obd.campaign;

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

/**
 * A single execution/run of a campaign.
 * <p>
 * Campaign configuration describes <em>what</em> should be executed;
 * CampaignExecution represents <em>one attempt</em> to execute it.
 * The future execution engine owns runtime state transitions.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "campaign_executions", indexes = {
    @Index(name = "idx_campaign_executions_tenant_deleted", columnList = "tenant_id,deleted_at"),
    @Index(name = "idx_campaign_executions_campaign", columnList = "campaign_id"),
    @Index(name = "idx_campaign_executions_status", columnList = "status")
})
public class CampaignExecution extends AuditableEntity {

    /** The campaign being executed. */
    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    /** Campaign's tenant (denormalized for scoped lookups). */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Execution lifecycle state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CampaignExecutionStatus status = CampaignExecutionStatus.REQUESTED;

    /** Optional client-supplied idempotency key; unique per campaign when present. */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    /** When the execution was requested. */
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    /** User who requested the execution. */
    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    /** When the execution engine started processing. */
    @Column(name = "started_at")
    private Instant startedAt;

    /** When the execution finished (success or failure). */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** Failure reason when status is FAILED. */
    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    /**
     * The immutable configuration snapshot this execution runs on (VB-6A
     * correction). Materialized inside the execution-creation transaction
     * and never swapped afterwards; every attempt of this execution —
     * retries included — consumes this snapshot, so later campaign edits
     * can never alter a running execution. Mandatory: the database FK is
     * NOT NULL, so no execution exists without its snapshot.
     */
    @Column(name = "configuration_snapshot_id", nullable = false, updatable = false)
    private UUID configurationSnapshotId;
}