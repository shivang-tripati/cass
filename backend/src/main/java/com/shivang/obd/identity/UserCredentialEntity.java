package com.shivang.obd.identity;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    name = "user_credentials",
    uniqueConstraints = @UniqueConstraint(name = "uq_user_credentials_user_type", columnNames = {"user_id", "identity_type"})
)
public class UserCredentialEntity extends AuditableEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 30)
    private CredentialType identityType = CredentialType.PASSWORD;

    @Column(name = "credential_hash", nullable = false, length = 200)
    private String credentialHash;
}
