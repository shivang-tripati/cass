package com.shivang.obd.voice.call;

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
 * Represents one logical voice interaction (call session).
 * <p>
 * This is the universal voice lifecycle abstraction, not campaign-specific.
 * A Voice Blast call, a Contact Center call, or an AI call all share this model.
 * <p>
 * Campaign-specific execution concerns (retries, scheduling, contact targeting)
 * remain in {@link com.shivang.obd.campaign.CallAttempt}.
 * CallAttempt references a CallSession to link the campaign view to the voice view.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "call_sessions", indexes = {
    @Index(name = "idx_call_sessions_tenant_deleted", columnList = "tenant_id,deleted_at"),
    @Index(name = "idx_call_sessions_status", columnList = "status"),
    @Index(name = "idx_call_sessions_started", columnList = "started_at"),
    @Index(name = "idx_call_sessions_direction", columnList = "direction")
})
public class CallSession extends AuditableEntity {

    /** Owning tenant. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Optional reseller for hierarchical ownership. */
    @Column(name = "reseller_id")
    private UUID resellerId;

    /** Call direction. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private CallDirection direction = CallDirection.OUTBOUND;

    /** Call purpose/type. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "call_type", nullable = false, length = 30)
    private CallType callType = CallType.VOICE_BLAST;

    /** Call lifecycle status. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CallSessionStatus status = CallSessionStatus.INITIATED;

    /** The DID used as caller ID. */
    @Column(name = "did_id")
    private UUID didId;

    /** Destination number (E.164). */
    @Column(name = "destination_number", length = 20)
    private String destinationNumber;

    /** When the session was initiated (dial requested). */
    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    /** When the call was answered (if ever). */
    @Column(name = "answered_at")
    private Instant answeredAt;

    /** When the call ended (completed or failed). */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** Standardized failure code. */
    @Column(name = "failure_code", length = 50)
    private String failureCode;

    /** Human-readable failure reason. */
    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    /** FreeSWITCH channel UUID for the primary leg (correlation). */
    @Column(name = "provider_call_id", length = 128)
    private String providerCallId;

    /**
     * VB-6E: the authoritative expiry instant for this call, computed once at
     * answer time as {@code answeredAt + frozenMaxCallDurationSeconds} and
     * persisted.
     *
     * <p>Persisting the deadline rather than recomputing it is what makes the
     * maximum-duration sweep a single indexed range scan and, more importantly,
     * what makes the timeout <b>idempotent</b>: a session past its deadline is
     * terminal for timeout purposes however many times it is examined, and a
     * duplicate or late ESL event cannot move the deadline or resurrect the
     * call.
     *
     * <p>{@code null} means "not governed by this control" — the session was
     * never answered, is not a governed call type, or predates VB-6E. Null
     * sessions are never swept.
     */
    @Column(name = "deadline_at")
    private java.time.Instant deadlineAt;

    /** Gateway used for this session (for capacity tracking). */
    @Column(name = "gateway_id")
    private UUID gatewayId;

    /** Optional reference to the campaign attempt that created this session. */
    @Column(name = "call_attempt_id")
    private UUID callAttemptId;

    /** Optional reference to the campaign execution. */
    @Column(name = "campaign_execution_id")
    private UUID campaignExecutionId;
}