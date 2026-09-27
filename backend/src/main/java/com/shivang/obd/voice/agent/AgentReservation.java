package com.shivang.obd.voice.agent;

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
 * One agent's atomic hold on a call (VB-3). Created inside the advisory
 * lock during {@code AgentReservationService.reserve}; counts against
 * {@code agents.max_concurrent_calls} while not RELEASED.
 * <p>
 * Lifecycle: RESERVED → ACTIVE (agent leg answered) → RELEASED (terminal).
 * Every transition is a conditional UPDATE so a racing thread can never
 * double-claim or double-release. Stale RESERVED rows are reclaimed by the
 * reconciler (same pattern as VB-0 voice-channel reservations).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "agent_reservations", indexes = {
    @Index(name = "idx_agent_reservations_agent_active", columnList = "agent_id,status"),
    @Index(name = "idx_agent_reservations_tenant", columnList = "tenant_id,deleted_at"),
    @Index(name = "idx_agent_reservations_stale_scan", columnList = "status,reserved_at")
})
public class AgentReservation extends AuditableEntity {

    /** Reserved agent. */
    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** Owning tenant. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The call that holds this reservation. */
    @Column(name = "call_session_id", nullable = false)
    private UUID callSessionId;

    /** The agent leg created for this connection (set at originate). */
    @Column(name = "call_leg_id")
    private UUID callLegId;

    /** Campaign attempt correlation (null for non-campaign calls). */
    @Column(name = "attempt_id")
    private UUID attemptId;

    /** Queue the reservation belongs to (ACD assignments only; null for VB-3 CONNECT_BY_AGENT holds). */
    @Column(name = "queue_id")
    private UUID queueId;

    /** Waiting call this reservation was created for (ACD assignments only). */
    @Column(name = "waiting_call_id")
    private UUID waitingCallId;

    /**
     * Bounded hold window for ACD reservations: if the consuming (VB-4D)
     * flow never claims the assignment, the ACD reconciler releases the
     * hold past this instant. Null for VB-3 holds (stale reconciler owns
     * those by {@code reserved_at}).
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Reservation lifecycle. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AgentReservationStatus status = AgentReservationStatus.RESERVED;

    /** When the hold was claimed. */
    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt = Instant.now();

    /** When the hold was released (terminal). */
    @Column(name = "released_at")
    private Instant releasedAt;

    /** Why the hold was released (audit). */
    @Column(name = "release_reason", length = 120)
    private String releaseReason;
}
