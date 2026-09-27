package com.shivang.obd.security.token;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Centralized security-response authority for current and future flows
 * (logout, reuse detection, password change, suspicious activity, admin).
 *
 * <p>Invariants: every family/user revocation is ownership-checked via the
 * user_id predicate; compromised-family revocation commits in an independent
 * transaction (REQUIRES_NEW) with reason TOKEN_REUSE and can never resurrect
 * a revoked family (all updates are conditional on revoked_at IS NULL);
 * operations are idempotent.</p>
 */
@Service
public class SecurityResponseService {

    private final RefreshTokenRevocationService revocationService;
    private final RefreshTokenFamilyRevoker compromisedFamilyRevoker;

    public SecurityResponseService(
        RefreshTokenRevocationService revocationService,
        RefreshTokenFamilyRevoker compromisedFamilyRevoker
    ) {
        this.revocationService = revocationService;
        this.compromisedFamilyRevoker = compromisedFamilyRevoker;
    }

    /** Ownership-aware single-session revocation. Idempotent. */
    @Transactional
    public int revokeFamily(UUID userId, UUID familyId, RevocationReason reason) {
        return revocationService.revokeFamilyForUser(userId, familyId, reason);
    }

    /** Revokes every active session of the user. Idempotent. */
    @Transactional
    public int revokeAllUserSessions(UUID userId, RevocationReason reason) {
        return revocationService.revokeAllUserSessions(userId, reason);
    }

    /**
     * Confirmed-compromise path (token reuse): independent-transaction
     * family kill with TOKEN_REUSE reason so a subsequent generic-error
     * rollback cannot undo it.
     */
    public void revokeCompromisedFamily(UUID familyId) {
        compromisedFamilyRevoker.revokeCompromisedFamily(familyId);
    }
}
