package com.shivang.obd.contact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * The relationship between a {@link ContactGroupEntity group} and a
 * {@link ContactEntity contact} (VB-6B.1). A Contact is a tenant-level
 * identity and belongs to zero or more groups; the group's audience is
 * exactly its live memberships.
 *
 * <p>The row is a pure relationship, mapped exactly to the physical V46
 * table: no per-membership contact fields (that would recreate the
 * duplication the identity model removes) and <strong>no soft
 * delete</strong> — membership rows are physical and removed when the
 * relationship ends. Tenant consistency
 * ({@code member.tenant = contact.tenant = group.tenant}) is enforced by
 * the composite foreign keys of V46, not by application code; this entity
 * deliberately does not extend {@code AuditableEntity} because the table
 * has no deletion columns.</p>
 *
 * <p>VB-6B.1 scope: schema ownership and repository access only.
 * Membership service/API behavior belongs to VB-6B.2.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "contact_group_members",
    uniqueConstraints = @UniqueConstraint(name = "uq_cgm_group_contact",
        columnNames = {"contact_group_id", "contact_id"}),
    indexes = {
        @Index(name = "idx_cgm_contact", columnList = "contact_id"),
        @Index(name = "idx_cgm_tenant", columnList = "tenant_id")
    })
public class ContactGroupMemberEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Owning tenant (denormalized; DB-enforced equal to both parents). */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** The audience/list containing the contact. */
    @Column(name = "contact_group_id", nullable = false, updatable = false)
    private UUID contactGroupId;

    /** The tenant-level contact identity participating in the group. */
    @Column(name = "contact_id", nullable = false, updatable = false)
    private UUID contactId;

    /** When the membership was created (DB default now()). */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    /** Who created the membership (stamped by ContactGroupMemberService). */
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    /**
     * Read-only view of the member contact, joined over the shared
     * {@code contact_id} FK value (VB-6B.2). Purely for queries and
     * responses — membership writes never touch the contact (the
     * identity belongs to {@link ContactIdentityService}), so the mapping
     * is {@code insertable=false, updatable=false} and never cascades.
     * Roster queries order/search and filter on the live contact through
     * this path; soft-deleted contacts are excluded by the queries, not
     * by this mapping.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", insertable = false, updatable = false)
    private ContactEntity contact;
}
