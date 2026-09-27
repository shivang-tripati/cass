package com.shivang.obd.contact;

import tools.jackson.databind.JsonNode;
import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A tenant-level callable contact identity (VB-6B.1). The canonical
 * identity is {@code (tenant_id, phone_number)} with live uniqueness
 * enforced by the database ({@code uq_contacts_tenant_phone_live}) — the
 * same number in two tenants is two Contacts; within one tenant it is
 * exactly one live Contact regardless of how many audiences contain it.
 *
 * <p>A Contact belongs to NO group: group participation is expressed by
 * {@link ContactGroupMemberEntity} rows (many-to-many). The phone number
 * is the dialing identity; {@code attributes} is the documented extension
 * point for future template-variable substitution. A soft-deleted Contact
 * stops being a live identity (its phone becomes re-creatable) while its
 * row and call history remain.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "contacts", indexes = {
    @Index(name = "idx_contacts_tenant_deleted", columnList = "tenant_id,deleted_at")
})
public class ContactEntity extends AuditableEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    /** Canonical E.164 number, e.g. "+918012345678" — unique per live tenant identity. */
    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "email", length = 255)
    private String email;

    /**
     * Genuinely dynamic per-contact context for template variables
     * (e.g. {"orderId": "ORD-12345"}); never used for core fields.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes")
    private JsonNode attributes;
}
