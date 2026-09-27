package com.shivang.obd.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * The physical per-attempt usage ledger (VB-6C.1): exactly one row per
 * provider-accepted dial, unique on {@code call_attempt_id} at the
 * database level. The unique key is what makes duplicate acceptance
 * handling (replayed +OK processing, duplicated internal events) a
 * no-op rather than a second count — the guarantee is physical, not
 * behavioral. Like the bucket row, this is accounting state: no
 * soft-delete columns.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "voice_blast_daily_usage_entries",
    uniqueConstraints = @UniqueConstraint(name = "uq_vbdue_call_attempt",
        columnNames = "call_attempt_id"),
    indexes = {
        @Index(name = "idx_vbdue_bucket", columnList = "tenant_id, contact_id, did_id, usage_date"),
        @Index(name = "idx_vbdue_tenant", columnList = "tenant_id")
    })
public class VoiceBlastDailyUsageEntry {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Isolation boundary. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** THE idempotency key — one entry per CallAttempt, ever. */
    @Column(name = "call_attempt_id", nullable = false, updatable = false)
    private UUID callAttemptId;

    /** Denormalized bucket components (mirrors the bucket key exactly). */
    @Column(name = "contact_id", nullable = false, updatable = false)
    private UUID contactId;

    /** Actual outbound DNID used for the dial. */
    @Column(name = "did_id", nullable = false, updatable = false)
    private UUID didId;

    @Column(name = "usage_date", nullable = false, updatable = false)
    private LocalDate usageDate;

    /** FreeSWITCH channel UUID from the accepted originate (+OK &lt;uuid&gt;). */
    @Column(name = "provider_call_id", length = 128)
    private String providerCallId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;
}
