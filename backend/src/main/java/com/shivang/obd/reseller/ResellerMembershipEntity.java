package com.shivang.obd.reseller;

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
    name = "reseller_memberships",
    uniqueConstraints = @UniqueConstraint(name = "uq_reseller_memberships_user_reseller", columnNames = {"user_id", "reseller_id"}),
    indexes = {
        @Index(name = "idx_reseller_memberships_user", columnList = "user_id"),
        @Index(name = "idx_reseller_memberships_reseller", columnList = "reseller_id"),
        @Index(name = "idx_reseller_memberships_role", columnList = "role_id")
    }
)
public class ResellerMembershipEntity extends AuditableEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "reseller_id", nullable = false)
    private UUID resellerId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "organizational_home_id", nullable = false)
    private UUID organizationalHomeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LifecycleStatus status = LifecycleStatus.ACTIVE;
}
