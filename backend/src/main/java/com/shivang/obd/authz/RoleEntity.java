package com.shivang.obd.authz;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "roles", indexes = @Index(name = "idx_roles_scope", columnList = "scope"))
public class RoleEntity extends AuditableEntity {

    @Column(name = "key", nullable = false, unique = true, length = 80)
    private String key;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 300)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private Scope scope;

    @Column(name = "system_defined", nullable = false)
    private boolean systemDefined;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
