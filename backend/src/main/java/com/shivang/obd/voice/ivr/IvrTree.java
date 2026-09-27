package com.shivang.obd.voice.ivr;

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
 * A reusable, tenant-owned IVR tree (VB-6F).
 * <p>
 * The resource VB-6F exists to add. Before it, an IVR flow lived inside each
 * campaign's {@code type_config} JSONB, so an identical menu had to be authored
 * once per campaign and every change was N edits. A tree is authored once and
 * referenced by any number of campaigns.
 * <p>
 * <b>Exactly one root.</b> The root is required for a tree to be
 * {@link IvrTreeStatus#ACTIVE}, which is the only state that can be selected for
 * execution or captured into a snapshot, so "one root" is enforced before
 * anything can be dialled. The column is nullable only so a tree and its root
 * can be inserted in one transaction; V53 defers the root FK to support that.
 * <p>
 * Nodes and transitions are separate entities rather than a JSONB blob because a
 * tree is a navigable, inspectable, constraint-bearing resource: §7 of the VB-6F
 * brief asks for a decision here, and the repository's own convention for
 * resources ({@code audio_assets}, {@code did}, {@code tts_templates}) is
 * typed relational, not opaque JSON. JSONB is used for the frozen execution
 * snapshot, which is the one place a bounded validated value is the better
 * representation.
 * <p>
 * {@code sourceCampaignId} records provenance for trees produced by the
 * create-IVR-from-campaign conversion. It is informational only and is never
 * used to reverse the conversion.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ivr_trees", indexes = {
    @Index(name = "idx_ivr_trees_tenant", columnList = "tenant_id, deleted_at"),
    @Index(name = "idx_ivr_trees_status", columnList = "tenant_id, status, deleted_at")
})
public class IvrTree extends AuditableEntity {

    /** Owning tenant. Every lookup is scoped by this, so a foreign tree is simply not found. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IvrTreeStatus status = IvrTreeStatus.DRAFT;

    /**
     * The single entry node. Constrained by V53 to belong to this tree.
     * <p>
     * Held as a plain id rather than a {@code @ManyToOne} so the entity model
     * stays free of associations that would fight the explicit bulk-replace
     * write path the service uses, and so "exactly one root" stays a validation
     * rule rather than something Hibernate can silently violate.
     */
    @Column(name = "root_node_id")
    private UUID rootNodeId;

    /** The campaign this tree was derived from, when produced by the conversion. */
    @Column(name = "source_campaign_id")
    private UUID sourceCampaignId;

    /** Whether this tree may be attached to a campaign and captured into a snapshot. */
    public boolean selectable() {
        return status == IvrTreeStatus.ACTIVE;
    }
}
