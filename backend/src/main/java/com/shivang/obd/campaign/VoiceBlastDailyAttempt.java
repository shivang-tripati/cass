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
 * The daily Voice Blast campaign-attempt safety bucket (VB-6D.3).
 *
 * <p><b>Distinct from {@link VoiceBlastDailyUsage} (VB-6C) — deliberately.</b>
 * These are two different controls and are not interchangeable:
 *
 * <table border="1">
 *   <caption>VB-6C vs VB-6D</caption>
 *   <tr><th></th><th>VB-6C (usage)</th><th>VB-6D (attempts)</th></tr>
 *   <tr><td>Question</td>
 *       <td>may another provider-ACCEPTED dial occur?</td>
 *       <td>may this contact be DISPATCHED to again?</td></tr>
 *   <tr><td>Key</td>
 *       <td>tenant + contact + actual DNID + day</td>
 *       <td>tenant + contact + day</td></tr>
 *   <tr><td>Counted at</td>
 *       <td>ESL {@code +OK} (provider acceptance)</td>
 *       <td>dial issuance</td></tr>
 *   <tr><td>Ceiling</td>
 *       <td>3</td><td>10</td></tr>
 *   <tr><td>Protects</td>
 *       <td>the subscriber's connections</td>
 *       <td>blast volume against infinite redial</td></tr>
 * </table>
 *
 * <p><b>No did_id is the whole point.</b> VB-6C's bucket is DNID-scoped, so
 * routing through a different number yields a different bucket. That is
 * correct for a connection-counting control but useless against the pattern
 * this one exists to stop: three campaigns each dialling the same contact
 * three times through rotating DNIDs. Removing {@code did_id} from the key
 * makes the ceiling genuinely global across every Voice Blast campaign of
 * the tenant for that contact that day.
 *
 * <p><b>One counter, no reservations.</b> The increment happens in the same
 * transaction that issues the dial, so consumption is immediate and
 * irreversible — there is no hold to strand if a process dies, which is the
 * one weakness of VB-6C's reserve/confirm design. The row is physical
 * accounting state: no soft-delete columns and not an
 * {@code AuditableEntity}, matching {@link VoiceBlastDailyUsage}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "voice_blast_daily_attempts",
    uniqueConstraints = @UniqueConstraint(name = "uq_vbda_bucket",
        columnNames = {"tenant_id", "contact_id", "usage_date"}),
    indexes = @Index(name = "idx_vbda_contact_date", columnList = "contact_id, usage_date"))
public class VoiceBlastDailyAttempt {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Isolation boundary — part of the database bucket key. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** The contact being dialed — part of the database bucket key. */
    @Column(name = "contact_id", nullable = false, updatable = false)
    private UUID contactId;

    /**
     * Calendar day in the execution snapshot's IANA timezone, resolved by
     * the same seam that defines "today" for VB-6C, so both controls roll
     * over at exactly the same instant.
     */
    @Column(name = "usage_date", nullable = false, updatable = false)
    private LocalDate usageDate;

    /**
     * Dials issued to this contact today. Never decremented, which is what
     * makes a stranded value structurally impossible here.
     */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
