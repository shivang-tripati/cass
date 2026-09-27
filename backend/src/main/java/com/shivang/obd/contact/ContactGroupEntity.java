package com.shivang.obd.contact;

import java.util.UUID;

import com.shivang.obd.common.audit.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tenant-owned contact group aggregate root. Campaign references groups
 * by identifier only; the group's tenant is the ownership boundary its
 * contacts inherit.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "contact_groups", indexes = {
    @Index(name = "idx_contact_groups_tenant_deleted", columnList = "tenant_id,deleted_at")
})
public class ContactGroupEntity extends AuditableEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description")
    private String description;
}

