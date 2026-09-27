package com.shivang.obd.tts;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A TTS text template owned at one of two scopes (V42): TENANT rows are
 * tenant-owned (tenant_id NOT NULL, tenant-created rows start
 * PENDING_APPROVAL); GLOBAL rows are platform-owned (tenant_id NULL,
 * managed only with platform authorization, APPROVED rows usable by
 * every tenant). Variables are simple named placeholders ({{name}})
 * declared through a typed schema; the text must reference only declared
 * variables. Rendering/synthesis belongs to the future execution layer —
 * this domain owns definition + approval only.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tts_templates", indexes = {
    @Index(name = "idx_tts_templates_tenant_deleted", columnList = "tenant_id,deleted_at")
})
public class TtsTemplateEntity extends AuditableEntity {

    /**
     * Owning tenant. NULL exactly for GLOBAL (platform-owned) rows; the
     * scope/tenancy mutual exclusivity is enforced by the V42 CHECK
     * constraints, never by application convention.
     */
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "template_text", nullable = false, columnDefinition = "text")
    private String templateText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables")
    private List<TtsTemplateVariable> variables;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TtsTemplateStatus status = TtsTemplateStatus.PENDING_APPROVAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private TtsTemplateScope scope = TtsTemplateScope.TENANT;
}
