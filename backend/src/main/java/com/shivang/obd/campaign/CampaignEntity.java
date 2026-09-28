package com.shivang.obd.campaign;

import tools.jackson.databind.JsonNode;
import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
 * Campaign aggregate root: a tenant-owned programmable outbound
 * communication workflow. Owns identity, type, lifecycle, schedule and
 * retry configuration, content selection, and the extension payloads for
 * type-specific and integration configuration.
 *
 * <p>External domains (contacts, audio assets, TTS templates, number
 * inventory) are referenced by UUID only — campaign never duplicates
 * their data, and no FK is created to tables those future modules own.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "campaigns", indexes = {
    @Index(name = "idx_campaigns_tenant_deleted", columnList = "tenant_id,deleted_at"),
    @Index(name = "idx_campaigns_tenant_type", columnList = "tenant_id,campaign_type"),
    @Index(name = "idx_campaigns_status", columnList = "status")
})
public class CampaignEntity extends AuditableEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_type", nullable = false, length = 30)
    private CampaignType campaignType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_mode", nullable = false, length = 20)
    private CampaignRunMode runMode = CampaignRunMode.ONE_TIME;

    /** Reference into the future Contact module (group of contacts). */
    @Column(name = "contact_group_id")
    private UUID contactGroupId;

    /** Reference into the DID module (caller-id number used by this campaign). */
    @Column(name = "did_id")
    private UUID didId;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_mode", length = 10)
    private ContentMode contentMode;

    /** Approved audio asset reference; owned by the future Audio module. */
    @Column(name = "audio_asset_id")
    private UUID audioAssetId;

    /**
     * Approved TTS template reference; owned by the future TTS module,
     * which also holds the template text and its variable schema. Only
     * APPROVED templates may be referenced by production campaigns.
     */
    @Column(name = "tts_template_id")
    private UUID ttsTemplateId;

    @Embedded
    private ScheduleSpec schedule;

    @Embedded
    private RetryPolicySpec retryPolicy;

    /**
     * Type-specific configuration payload (DTMF input rules, agent/queue
     * routing reference). Required as a non-empty JSON object for DTMF
     * and CONNECT_BY_AGENT campaigns; field-level schemas are defined by
     * future product decisions and validated then.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "type_config")
    private JsonNode typeConfig;

    /**
     * Campaign integration and reporting-privacy configuration.
     *
     * <p>VB-7C.2: the column is unchanged, but its content is no longer free-form.
     * It is written only as the canonical serialization of the typed
     * {@link com.shivang.obd.campaign.config.CampaignIntegrationConfig}, which parses
     * strictly and rejects unknown fields. The previously documented "secrets are
     * prohibited here" is therefore now an <em>enforced</em> contract rather than a
     * comment, and no credential can be persisted through this column.
     *
     * <p>Storage stays {@code JSONB} deliberately: no migration is required, and
     * this mirrors how {@code type_config} already carries typed configuration.
     *
     * <p>Read it through
     * {@link com.shivang.obd.campaign.config.CampaignIntegrationConfig#fromJson}
     * rather than by casting the node.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "integration_config")
    private JsonNode integrationConfig;

/**
 * Lineage version. Roots start at 1; cloning creates a new campaign
 * with source.version + 1 and a clonedFromCampaignId pointer.
 * Server-managed only — never client-writable, never bumped by
 * ordinary updates. Not an immutable-revision system.
 */
@Column(name = "version", nullable = false)
private Integer version = 1;

/** Provenance pointer to the campaign this one was cloned from. */
@Column(name = "cloned_from_campaign_id")
private UUID clonedFromCampaignId;

/** When true, only numbers on tenant whitelist may be dialed (subject to higher blocks). */
@Column(name = "call_on_whitelist_numbers", nullable = false)
private Boolean callOnWhitelistNumbers = false;

/**
 * Optional campaign-specific Voice Blast daily dial limit (VB-6C.2):
 * the maximum provider-accepted dials per contact + actual outbound DNID
 * + calendar day for this campaign's executions. Valid values are 1-3
 * (enforced by DTO validation, the service-layer domain rule, and the
 * V48 database CHECK). Null means "not explicitly configured" — the
 * runtime then applies the platform maximum of 3; the configured value
 * and the effective policy value are deliberately distinct.
 * <p>
 * Execution-affecting configuration: copied into the immutable execution
 * snapshot at execution creation (VB-6A) — later edits to the campaign
 * never alter an already-created execution's limit.
 */
@Column(name = "daily_dial_limit")
private Integer dailyDialLimit;

/**
 * Optional campaign-specific ceiling on daily campaign ATTEMPTS for one
 * contact (VB-6D.3) - a genuinely different control from
 * {@code dailyDialLimit} above:
 *
 * <ul>
 *   <li>{@code dailyDialLimit} counts provider-ACCEPTED dials, per contact
 *       <em>per actual DNID</em> (VB-6C);</li>
 *   <li>{@code maxDailyAttempts} counts DISPATCHES, per contact per day
 *       across <em>every</em> Voice Blast campaign of the tenant (VB-6D).</li>
 * </ul>
 *
 * Null means "use the platform default". Execution-affecting
 * configuration: frozen into the immutable execution snapshot, so a later
 * campaign edit cannot change a running execution's ceiling.
 */
@Column(name = "max_daily_attempts")
private Integer maxDailyAttempts;

    /**
     * Optional maximum lifetime of an ESTABLISHED outbound call, in seconds
     * (VB-6E). Null means the platform default of
     * {@link MaxCallDurationPolicy#DEFAULT_MAX_CALL_DURATION_SECONDS} (300s).
     *
     * <p>Explicitly not a ring timeout, a provider connection timeout, or a
     * playback length - it bounds the active call session from the moment the
     * provider reports answer. Execution-affecting configuration: frozen into
     * the immutable execution snapshot, so a later campaign edit cannot
     * lengthen or shorten a running execution's call duration.
     */
    @Column(name = "max_call_duration_seconds")
    private Integer maxCallDurationSeconds;
}