package com.shivang.obd.authz;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "capabilities", indexes = @Index(name = "idx_capabilities_resource", columnList = "resource"))
public class CapabilityEntity extends AuditableEntity {

    @Column(name = "key", nullable = false, unique = true, length = 100)
    private String key;

    @Column(name = "resource", nullable = false, length = 60)
    private String resource;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
