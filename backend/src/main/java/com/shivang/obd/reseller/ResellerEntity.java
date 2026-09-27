package com.shivang.obd.reseller;

import com.shivang.obd.common.audit.AuditableEntity;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "resellers",
    uniqueConstraints = @UniqueConstraint(name = "uq_resellers_custom_domain", columnNames = {"custom_domain"})
)
public class ResellerEntity extends AuditableEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 100)
    private String slug;

    @Column(name = "display_name", length = 150)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LifecycleStatus status = LifecycleStatus.ACTIVE;

    @Column(name = "support_email", length = 255)
    private String supportEmail;

    @Column(name = "custom_domain", length = 255)
    private String customDomain;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "primary_color", length = 20)
    private String primaryColor;
}
