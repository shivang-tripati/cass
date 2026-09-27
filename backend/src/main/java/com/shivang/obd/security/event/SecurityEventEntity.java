package com.shivang.obd.security.event;

import com.shivang.obd.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only security event. Deliberately free of HTTP concepts and of any
 * credential/token material; cross-module user reference is a bare UUID with
 * no FK so unknown/deleted users remain recordable.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "security_events",
    indexes = {
        @Index(name = "idx_security_events_user", columnList = "user_id"),
        @Index(name = "idx_security_events_type", columnList = "event_type"),
        @Index(name = "idx_security_events_occurred_at", columnList = "occurred_at")
    }
)
public class SecurityEventEntity extends BaseEntity {

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private SecurityEventType eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "success", nullable = false)
    private boolean success;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, String> metadata = new HashMap<>();
}
