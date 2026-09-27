package com.shivang.obd.tenant;

import com.shivang.obd.common.audit.AuditableEntity;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
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

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "tenant_memberships",
    uniqueConstraints = @UniqueConstraint(name = "uq_tenant_memberships_user_tenant", columnNames = {"user_id", "tenant_id"}),
    indexes = {
        @Index(name = "idx_tenant_memberships_user", columnList = "user_id"),
        @Index(name = "idx_tenant_memberships_tenant", columnList = "tenant_id"),
        @Index(name = "idx_tenant_memberships_role", columnList = "role_id")
    }
)
public class TenantMembershipEntity extends AuditableEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "organizational_home_id", nullable = false)
    private UUID organizationalHomeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LifecycleStatus status = LifecycleStatus.ACTIVE;
}
