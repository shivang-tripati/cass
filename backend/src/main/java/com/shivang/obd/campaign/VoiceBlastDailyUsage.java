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
 * The atomic daily dial-limit usage bucket for Voice Blast campaigns
 * (VB-6C.1).
 * <p>
 * Identity (the policy key): {@code (tenantId, contactId, actualOutboundDidId,
 * usageDate)} — unique at the database level, campaign-agnostic on purpose:
 * two campaigns dialing the same contact through the same DNID share one
 * platform bucket within the tenant.
 * <p>
 * Two counters with distinct roles:
 * <ul>
 *   <li>{@code reservedCount} — slots currently held by admitted-but-not-yet-
 *       accepted dials. Every grant is either confirmed at provider
 *       acceptance (+OK) or explicitly released (any pre-acceptance
 *       failure), so holds are short-lived.</li>
 *   <li>{@code usedCount} — provider-ACCEPTED dials. THE compliance
 *       figure: ringing/busy/no-answer/failure after acceptance still
 *       counts; every pre-acceptance rejection does not.</li>
 * </ul>
 * Admission and confirmation are single-statement conditional UPDATEs on
 * this row (see {@link VoiceBlastDailyUsageRepository}), so the limit
 * invariant is enforced by PostgreSQL row locking — no application-level
 * read-then-write race exists. The row is physical accounting state: no
 * soft-delete columns, not an {@code AuditableEntity} (same rationale as
 * {@code contact_group_members}, V46).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "voice_blast_daily_usage",
    uniqueConstraints = @UniqueConstraint(name = "uq_vbdu_bucket",
        columnNames = {"tenant_id", "contact_id", "did_id", "usage_date"}),
    indexes = @Index(name = "idx_vbdu_contact_date", columnList = "contact_id, usage_date"))
public class VoiceBlastDailyUsage {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Isolation boundary — part of the database bucket key. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** The contact identity being dialed — part of the database bucket key. */
    @Column(name = "contact_id", nullable = false, updatable = false)
    private UUID contactId;

    /**
     * The ACTUAL outbound DNID (routing-selected {@code VoiceRoute.didId}),
     * not the campaign's requested DID when routing substituted one.
     */
    @Column(name = "did_id", nullable = false, updatable = false)
    private UUID didId;

    /** Calendar day in the execution snapshot's IANA timezone. */
    @Column(name = "usage_date", nullable = false, updatable = false)
    private LocalDate usageDate;

    @Column(name = "reserved_count", nullable = false)
    private int reservedCount = 0;

    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
