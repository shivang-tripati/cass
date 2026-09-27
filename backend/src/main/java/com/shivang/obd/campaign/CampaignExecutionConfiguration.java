package com.shivang.obd.campaign;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * The immutable configuration snapshot one {@link CampaignExecution} runs
 * on (VB-6A correction). Created inside the execution-creation transaction
 * and never updated afterwards: it records <em>what this particular
 * execution was created to execute</em>, not a historical version of the
 * campaign. The campaign itself is never versioned — while it is editable
 * (DRAFT), edits simply change its current configuration; each new
 * execution snapshots that configuration at creation time.
 * <p>
 * Ownership is mandatory and database-enforced:
 * {@code campaign_executions.configuration_snapshot_id} is NOT NULL with a
 * foreign key to this table, and the snapshot row is inserted before the
 * execution row in the same transaction — no execution exists without
 * exactly one snapshot, and no orphan snapshot exists without its
 * execution.
 * <p>
 * A snapshot records <em>what the campaign requested</em>; whether that
 * request is currently allowed (DID/audio/TTS state, compliance, capacity)
 * remains the job of the existing runtime validation stack. Immutability is
 * enforced in depth: no update API exists and the entity is
 * {@link Immutable @Immutable} at the JPA level.
 */
@Getter
@Entity
@Table(name = "campaign_execution_configurations", indexes = {
    @Index(name = "idx_cec_campaign", columnList = "campaign_id"),
    @Index(name = "idx_cec_tenant", columnList = "tenant_id")
})
@Immutable
@NoArgsConstructor
public class CampaignExecutionConfiguration extends AuditableEntity {

    /** Owning campaign (plain reference by design — no JPA association). */
    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    /** Owning tenant (isolation boundary for every read). */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Immutable configuration payload. */
    @Embedded
    private CampaignConfigurationSnapshot configuration;

    /** Materialization timestamp. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Factories only — no mutation after creation. */
    static CampaignExecutionConfiguration materialize(
            UUID campaignId,
            UUID tenantId,
            CampaignConfigurationSnapshot configuration,
            Instant createdAt) {
        CampaignExecutionConfiguration entity = new CampaignExecutionConfiguration();
        entity.campaignId = campaignId;
        entity.tenantId = tenantId;
        entity.configuration = configuration;
        entity.createdAt = createdAt;
        return entity;
    }
}
