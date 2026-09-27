package com.shivang.obd.authz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "platform_role_assignments",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_platform_role_assignments_user_role",
        columnNames = {"user_id", "role_id"}
    ),
    indexes = @Index(name = "idx_platform_role_assignments_user", columnList = "user_id")
)
public class PlatformRoleAssignmentEntity {

    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
