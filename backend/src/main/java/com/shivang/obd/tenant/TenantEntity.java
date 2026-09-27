package com.shivang.obd.tenant;

import com.shivang.obd.common.audit.AuditableEntity;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
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

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tenants", indexes = @Index(name = "idx_tenants_reseller", columnList = "reseller_id"))
public class TenantEntity extends AuditableEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 100)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LifecycleStatus status = LifecycleStatus.ACTIVE;

    @Column(name = "reseller_id")
    private UUID resellerId;
}
