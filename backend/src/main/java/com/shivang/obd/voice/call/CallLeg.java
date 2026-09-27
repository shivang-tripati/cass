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
 * Represents one leg within a call session.
 * <p>
 * A call session may have multiple legs:
 * - CONNECT_BY_AGENT: CustomerLeg + AgentLeg
 * - External forwarding: CustomerLeg + ExternalAgentLeg
 * - Future AI: CustomerLeg + AILeg
 * - Simple outbound: single CustomerLeg
 * <p>
 * This entity is created when a leg is established and tracks its individual lifecycle.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "call_legs", indexes = {
    @Index(name = "idx_call_legs_session", columnList = "call_session_id"),
    @Index(name = "idx_call_legs_type", columnList = "leg_type"),
    @Index(name = "idx_call_legs_status", columnList = "status"),
    @Index(name = "idx_call_legs_provider_call_id", columnList = "provider_call_id")
})
public class CallLeg extends AuditableEntity {

    /** The call session this leg belongs to. */
    @Column(name = "call_session_id", nullable = false)
    private UUID callSessionId;

    /** Leg type. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "leg_type", nullable = false, length = 30)
    private CallLegType legType;

    /** Endpoint type for this leg. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "endpoint_type", length = 30)
    private EndpointType endpointType;

    /** Call direction for this leg. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private CallDirection direction;

    /** Leg lifecycle status. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CallLegStatus status = CallLegStatus.INITIATED;

    /** Destination/target for this leg (E.164 number, SIP URI, agent ID, etc.). */
    @Column(name = "target", length = 255)
    private String target;

    /** FreeSWITCH channel UUID for this specific leg. */
    @Column(name = "provider_call_id", length = 128)
    private String providerCallId;

    /** When the leg was initiated. */
    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    /** When the leg was answered. */
    @Column(name = "answered_at")
    private Instant answeredAt;

    /** When the leg ended. */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** Failure code if leg failed. */
    @Column(name = "failure_code", length = 50)
    private String failureCode;

    /** Human-readable failure reason. */
    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    /** Optional: agent ID if this is an agent leg. */
    @Column(name = "agent_id")
    private UUID agentId;

    /** Optional: queue ID if this leg is queued. */
    @Column(name = "queue_id")
    private UUID queueId;
}