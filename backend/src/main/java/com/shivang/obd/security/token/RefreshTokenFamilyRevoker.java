package com.shivang.obd.security.token;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compromise-response revocation committed in an INDEPENDENT transaction:
 * reuse detection throws a generic authentication error afterwards, and the
 * rollback of that error path must not undo the family kill. Reason is
 * always TOKEN_REUSE.
 */
@Component
public class RefreshTokenFamilyRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenFamilyRevoker(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeCompromisedFamily(UUID familyId) {
        refreshTokenRepository.revokeActiveFamilyInternal(
            familyId, Instant.now(), RevocationReason.TOKEN_REUSE.name());
    }
}
