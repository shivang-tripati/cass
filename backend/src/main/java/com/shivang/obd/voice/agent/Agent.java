package com.shivang.obd.voice.agent;

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
 * An agent that can receive CONNECT_BY_AGENT calls (VB-3).
 * <p>
 * Tenant-scoped like every voice-domain row. Administrative status and
 * runtime availability are separate concepts (see the enums). Concurrency
 * budget is {@code maxConcurrentCalls} — an agent-level capacity completely
 * independent of the voice gateway channel budget (VB-0).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "agents", indexes = {
    @Index(name = "idx_agents_tenant", columnList = "tenant_id,deleted_at"),
    @Index(name = "idx_agents_selection", columnList = "tenant_id,admin_status,availability,deleted_at")
})
public class Agent extends AuditableEntity {

    /** Owning tenant — agents are never shared across tenants. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Human-readable name for operations/audit. */
    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    /** Administrative status — "allowed to receive calls". */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "admin_status", nullable = false)
    private AgentAdminStatus adminStatus = AgentAdminStatus.ACTIVE;

    /** Runtime availability — "can take a call right now". */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "availability", nullable = false)
    private AgentAvailability availability = AgentAvailability.OFFLINE;

    /** Concurrent-call budget for this agent (>= 1). */
    @Column(name = "max_concurrent_calls", nullable = false)
    private int maxConcurrentCalls = 1;

    /** Optional identity link (the AGENT-role user operating this agent). */
    @Column(name = "user_id")
    private UUID userId;
}
