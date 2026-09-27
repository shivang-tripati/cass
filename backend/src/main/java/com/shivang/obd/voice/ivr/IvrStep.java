package com.shivang.obd.voice.ivr;

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
 * Per-node live state for one active call (VB-6F).
 * <p>
 * <b>Why this table exists at all.</b> {@code dtmf_interactions} is one row per
 * call session — {@code findByCallSessionIdAndDeletedAtIsNull} returns an
 * {@code Optional}, not a list. That is correct for a single-level interaction
 * and structurally incapable of representing progress through a multi-level
 * tree: there is nowhere to record which node the caller is on, how many
 * invalid or no-input retries remain, or a per-node deadline. Rather than change
 * that table (which would break all 57 existing DTMF tests and the proven VB-2
 * runtime), IVR gets its own per-<em>visit</em> row. This is the IVR analogue of
 * {@code dtmf_interactions}, not a duplicate of it: different grain, different
 * lifecycle, one table each.
 * <p>
 * <b>The current node of a call is the newest non-terminal row for its
 * session</b> — one lookup, mirroring how the current DTMF interaction is found.
 * Nothing IVR-related is written to {@code call_sessions}, so there is never a
 * second source of truth for the caller's position.
 * <p>
 * <b>Nothing here is re-read from {@link IvrNode} at runtime.</b> {@code nodeKey}
 * names a key inside the immutable execution snapshot, and {@code inputWaitSeconds}
 * is a frozen copy, so editing a live IVR can neither move nor extend a deadline
 * a caller is already waiting on.
 * <p>
 * {@code expiresAt} is the deterministic timeout source, consumed by the
 * <em>existing</em> 1-second {@code DtmfTimeoutScheduler} scan. VB-6F adds no
 * scheduler and no timer framework.
 * <p>
 * Terminalization uses the same atomic-claim idiom as the DTMF runtime
 * ({@code UPDATE … WHERE id = :id AND result = 'WAITING_INPUT'}), so a duplicate
 * digit, a late digit, and a digit racing the timeout poller all resolve to
 * exactly one winner and no-ops for the losers. No second idempotency framework.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ivr_steps", indexes = {
    @Index(name = "idx_ivr_steps_session", columnList = "call_session_id, deleted_at"),
    @Index(name = "idx_ivr_steps_tenant", columnList = "tenant_id, deleted_at"),
    @Index(name = "idx_ivr_steps_timeout", columnList = "result, expires_at"),
    @Index(name = "idx_ivr_steps_attempt", columnList = "call_attempt_id")
})
public class IvrStep extends AuditableEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The call this visit belongs to. A call has one row per node it visits. */
    @Column(name = "call_session_id", nullable = false)
    private UUID callSessionId;

    @Column(name = "call_attempt_id")
    private UUID callAttemptId;

    @Column(name = "execution_id")
    private UUID executionId;

    /** The tree this visit is traversing. Recorded for audit; the snapshot is what is executed. */
    @Column(name = "tree_id", nullable = false)
    private UUID treeId;

    /** Frozen node key, naming the entry in the execution snapshot's node map. */
    @Column(name = "node_key", nullable = false, length = 64)
    private String nodeKey;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false)
    private IvrNodeType nodeType;

    /** Frozen copy of the node's input wait, so a live edit cannot move this deadline. */
    @Column(name = "input_wait_seconds", nullable = false)
    private Integer inputWaitSeconds;

    /**
     * Frozen copy of the node's invalid-input budget.
     * <p>
     * Present so a step is self-describing. The per-node timeout path is
     * dispatched with only a session id, so the node's budget has to be on the
     * step itself; resolving the execution to reach the snapshot on the timeout
     * path would be both slower and a place to accidentally read live IVR rows.
     * {@code dtmf_interactions} freezes its whole configuration for the same
     * reason.
     */
    @Column(name = "invalid_input_retries", nullable = false)
    private Integer invalidInputRetries = 0;

    /** Frozen copy of the node's no-input budget. */
    @Column(name = "no_input_retries", nullable = false)
    private Integer noInputRetries = 0;

    /** Invalid-input attempts made so far, including the first. */
    @Column(name = "invalid_attempts", nullable = false)
    private Integer invalidAttempts = 0;

    /** No-input attempts made so far, including the first. */
    @Column(name = "no_input_attempts", nullable = false)
    private Integer noInputAttempts = 0;

    /** The single source of truth for this visit's outcome. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false)
    private IvrStepResultType result = IvrStepResultType.WAITING_INPUT;

    @Column(name = "result_reason", length = 255)
    private String resultReason;

    @Column(name = "result_at")
    private Instant resultAt;

    /** Per-node deadline. Deterministic, and never recomputed on re-scan. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
