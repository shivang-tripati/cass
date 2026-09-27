package com.shivang.obd.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCredentialRepository extends JpaRepository<UserCredentialEntity, UUID> {

    Optional<UserCredentialEntity> findByUserIdAndIdentityType(UUID userId, CredentialType identityType);
}
