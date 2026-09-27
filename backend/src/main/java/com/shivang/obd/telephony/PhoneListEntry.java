package com.shivang.obd.telephony;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phone number list entry for call eligibility compliance.
 * Scope: platform, reseller, or tenant. Type determines blocking/allowing behavior.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "phone_lists",
    indexes = {
        @Index(name = "idx_phone_lists_number_type_scope", columnList = "normalized_number,type,scope_type,scope_reseller_id,scope_tenant_id,active,deleted_at"),
        @Index(name = "idx_phone_lists_scope", columnList = "scope_type,scope_reseller_id,scope_tenant_id"),
        @Index(name = "idx_phone_lists_type_active", columnList = "type,active,deleted_at")
    },
    uniqueConstraints = @UniqueConstraint(
        name = "uq_phone_lists_number_type_scope_active",
        columnNames = {"normalized_number", "type", "scope_type", "scope_reseller_id", "scope_tenant_id", "active", "deleted_at"}
    )
)
public class PhoneListEntry extends AuditableEntity {

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NAMED_ENUM)
    @Column(name = "type", nullable = false, length = 30)
    private PhoneListType type;

    /** Scope type: PLATFORM, RESELLER, TENANT */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NAMED_ENUM)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    /** Reseller ID when scope is RESELLER */
    @Column(name = "scope_reseller_id")
    private UUID scopeResellerId;

    /** Tenant ID when scope is TENANT */
    @Column(name = "scope_tenant_id")
    private UUID scopeTenantId;

    /** Normalized E.164 number, e.g. "+919876543210" */
    @Column(name = "normalized_number", nullable = false, length = 20)
    private String normalizedNumber;

    @Column(name = "original_number", length = 30)
    private String originalNumber;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "active", nullable = false)
    private Boolean active = true;
}