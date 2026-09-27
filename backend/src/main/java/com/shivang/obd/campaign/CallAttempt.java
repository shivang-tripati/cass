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
 * A single call attempt within a campaign execution.
 * <p>
 * Represents one attempted call for one contact during one campaign execution.
 * The future execution engine owns runtime state transitions.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "call_attempts", indexes = {
    @Index(name = "idx_call_attempts_tenant_deleted", columnList = "tenant_id,deleted_at"),
    @Index(name = "idx_call_attempts_execution", columnList = "execution_id"),
    @Index(name = "idx_call_attempts_campaign", columnList = "campaign_id"),
    @Index(name = "idx_call_attempts_status", columnList = "status"),
    @Index(name = "idx_call_attempts_scheduled", columnList = "scheduled_at"),
    @Index(name = "idx_call_attempts_contact", columnList = "contact_id"),
    @Index(name = "idx_call_attempts_did", columnList = "did_id")
})
public class CallAttempt extends AuditableEntity {

    /** The execution this attempt belongs to. */
    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    /** The campaign being executed (denormalized for queries). */
    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    /** Campaign's tenant (denormalized for scoped lookups). */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The contact being called. */
    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    /** The DID used for this attempt. */
    @Column(name = "did_id", nullable = false)
    private UUID didId;

    /** Attempt number for this contact within this execution (1-based). */
    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber = 1;

    /** Execution-oriented status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CallAttemptStatus status = CallAttemptStatus.QUEUED;

    /** When the attempt was scheduled for execution. */
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    /** When the execution worker started processing this attempt. */
    @Column(name = "started_at")
    private Instant startedAt;

    /** When the attempt finished (success or failure). */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** Standardized failure code (e.g. DIAL_FAILED, NO_ANSWER, BUSY, CONGESTION). */
    @Column(name = "failure_code", length = 50)
    private String failureCode;

    /** Human-readable failure reason. */
    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    /** FreeSWITCH channel UUID from originate response (for ESL event correlation). */
    @Column(name = "provider_call_id", length = 128)
    private String providerCallId;
}