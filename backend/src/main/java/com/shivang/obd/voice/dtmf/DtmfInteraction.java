package com.shivang.obd.voice.dtmf;

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
 * One auditable DTMF collection interaction (VB-2).
 * <p>
 * Persists the configuration snapshot in effect for this call (the campaign
 * config may change later), the collected digits, the terminal result and
 * its timing. Tenant-scoped like every voice table; references the call
 * session (and optionally the attempt/campaign) for correlation.
 * <p>
 * The {@code result} column is the single source of truth for the
 * interaction outcome — CallSessionStatus only carries the coarse
 * WAITING_FOR_DTMF state.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dtmf_interactions", indexes = {
    @Index(name = "idx_dtmf_interactions_session", columnList = "call_session_id"),
    @Index(name = "idx_dtmf_interactions_tenant", columnList = "tenant_id,deleted_at"),
    @Index(name = "idx_dtmf_interactions_timeout_scan", columnList = "result,expires_at"),
    @Index(name = "idx_dtmf_interactions_attempt", columnList = "call_attempt_id")
})
public class DtmfInteraction extends AuditableEntity {

    /** Owning tenant. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The call session this interaction collects input for. */
    @Column(name = "call_session_id", nullable = false)
    private UUID callSessionId;

    /** Optional campaign attempt correlation. */
    @Column(name = "call_attempt_id")
    private UUID callAttemptId;

    /** Optional campaign correlation. */
    @Column(name = "campaign_id")
    private UUID campaignId;

    // --- configuration snapshot (auditability) ---

    @Column(name = "expected_input", nullable = false, length = 16)
    private String expectedInput;

    @Column(name = "max_digits", nullable = false)
    private Integer maxDigits;

    @Column(name = "terminator", length = 1)
    private String terminator;

    @Column(name = "timeout_secs", nullable = false)
    private Integer timeoutSecs;

    // --- collection state ---

    /** Digits collected so far, in deterministic arrival order. */
    @Column(name = "collected_digits", nullable = false, length = 32)
    private String collectedDigits = "";

    /** Interaction result — single source of truth for the outcome. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false)
    private DtmfResultType result = DtmfResultType.COLLECTING;

    /** Human-readable terminal reason (e.g. mismatched digit). */
    @Column(name = "result_reason", length = 255)
    private String resultReason;

    /** When the terminal result was reached. */
    @Column(name = "result_at")
    private Instant resultAt;

    /** Collection deadline — deterministic timeout source for the poller. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Post-result action requested for this interaction (VB-2/VB-3):
     * TERMINATE (default) or CONNECT_BY_AGENT. Snapshotted at creation —
     * campaign config changes mid-call never alter the requested action.
     */
    @Column(name = "action_type", nullable = false, length = 40)
    private String actionType = com.shivang.obd.voice.dtmf.DtmfActions.TERMINATE;
}
