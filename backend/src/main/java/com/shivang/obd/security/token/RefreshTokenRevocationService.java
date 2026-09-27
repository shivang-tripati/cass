package com.shivang.obd.security.token;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Centralized authority for refresh-token/session revocation.
 *
 * <p>Session model: family_id IS the session; individual token rows are its
 * rotating credentials. All operations enforce ownership (user_id predicate)
 * and are idempotent (revoked_at IS NULL guard), so repeat calls succeed
 * without side effects.</p>
 *
 * <p>Concurrency with rotation: both claim-for-rotation and every revocation
 * here are conditional on revoked_at IS NULL. Whichever wins, the outcome is
 * safe — a revoked family can never become active again, and a rotation that
 * raced ahead is caught by the next revocation sweep or the family-wide
 * predicates above.</p>
 */
@Service
public class RefreshTokenRevocationService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenFamilyRevoker compromisedFamilyRevoker;
    private final RefreshTokenHasher hasher;

    public RefreshTokenRevocationService(
        RefreshTokenRepository refreshTokenRepository,
        RefreshTokenFamilyRevoker compromisedFamilyRevoker,
        RefreshTokenHasher hasher
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.compromisedFamilyRevoker = compromisedFamilyRevoker;
        this.hasher = hasher;
    }

    /**
     * Revokes one session (token family) if owned by the given user.
     * Returns affected row count (0 = already revoked or not owned).
     */
    @Transactional
    public int revokeFamilyForUser(UUID userId, UUID familyId, RevocationReason reason) {
        return refreshTokenRepository.revokeActiveFamilyForUser(
            familyId, userId, Instant.now(), reason.name());
    }

    /**
     * Revokes all active sessions of a user. Returns affected row count.
     */
    @Transactional
    public int revokeAllUserSessions(UUID userId, RevocationReason reason) {
        return refreshTokenRepository.revokeAllActiveForUser(userId, Instant.now(), reason.name());
    }

    /**
     * Reuse-detection path: commits in an independent transaction so the
     * subsequent generic-error rollback cannot undo the family kill.
     */
    public void revokeCompromisedFamily(UUID familyId) {
        compromisedFamilyRevoker.revokeCompromisedFamily(familyId);
    }

    /**
     * Unauthenticated (cookie-only) logout: the presented raw refresh token
     * is itself the bearer credential, so it resolves its own owner. The
     * resolved user_id predicate keeps revocation ownership-safe; unknown,
     * expired-used or already-revoked tokens are idempotent no-ops.
     * Returns the owning userId when a live session was revoked, empty
     * otherwise.
     */
    @Transactional
    public Optional<UUID> revokeByPresentedToken(String rawToken, RevocationReason reason) {
        return refreshTokenRepository.findByTokenHash(hasher.hash(rawToken))
            .filter(token -> token.getRevokedAt() == null)
            .filter(token -> revokeFamilyForUser(
                token.getUserId(), token.getFamilyId(), reason) > 0)
            .map(RefreshTokenEntity::getUserId);
    }
}
