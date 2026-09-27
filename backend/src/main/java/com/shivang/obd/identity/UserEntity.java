package com.shivang.obd.identity;

import com.shivang.obd.common.audit.AuditableEntity;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
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
@Table(name = "users", indexes = @Index(name = "idx_users_status", columnList = "status"))
public class UserEntity extends AuditableEntity {

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "normalized_email", nullable = false, length = 255)
    private String normalizedEmail;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LifecycleStatus status = LifecycleStatus.ACTIVE;
}
