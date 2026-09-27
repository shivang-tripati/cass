package com.shivang.obd.security.token;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /**
     * Atomic conditional state transition: only the first concurrent caller
     * claims an unused, unrevoked token. All other racing requests observe
     * zero updated rows and are treated as token reuse.
     */
    @Modifying
    @Query("""
        UPDATE RefreshTokenEntity r
        SET r.usedAt = :now, r.updatedAt = :now
        WHERE r.id = :id AND r.usedAt IS NULL AND r.revokedAt IS NULL
        """)
    int claimForRotation(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Revokes every still-active member of a family. Ownership is enforced
     * by the user_id predicate: a family can only be revoked through its
     * owning user.
     */
    @Modifying
    @Query("""
        UPDATE RefreshTokenEntity r
        SET r.revokedAt = :now, r.revocationReason = :reason, r.updatedAt = :now
        WHERE r.familyId = :familyId AND r.userId = :userId AND r.revokedAt IS NULL
        """)
    int revokeActiveFamilyForUser(
        @Param("familyId") UUID familyId,
        @Param("userId") UUID userId,
        @Param("now") Instant now,
        @Param("reason") String reason);

    /**
     * Revokes every active session (token family) of a user.
     */
    @Modifying
    @Query("""
        UPDATE RefreshTokenEntity r
        SET r.revokedAt = :now, r.revocationReason = :reason, r.updatedAt = :now
        WHERE r.userId = :userId AND r.revokedAt IS NULL
        """)
    int revokeAllActiveForUser(
        @Param("userId") UUID userId,
        @Param("now") Instant now,
        @Param("reason") String reason);

    /**
     * Compromise response: family-wide revocation without user predicate —
     * reserved exclusively for internal reuse detection where the family is
     * already proven to belong to the authenticated principal's chain.
     */
    @Modifying
    @Query("""
        UPDATE RefreshTokenEntity r
        SET r.revokedAt = :now, r.revocationReason = :reason, r.updatedAt = :now
        WHERE r.familyId = :familyId AND r.revokedAt IS NULL
        """)
    int revokeActiveFamilyInternal(
        @Param("familyId") UUID familyId,
        @Param("now") Instant now,
        @Param("reason") String reason);

    /**
     * Retention cleanup: batch-deletes only DEAD tokens (revoked, used, or
     * expired) whose terminal state is older than the retention cutoff.
     * Active tokens can never match. Returns deleted row count.
     */
    @Modifying
    @Query(value = """
        DELETE FROM refresh_tokens
        WHERE id IN (
            SELECT id FROM refresh_tokens
            WHERE (revoked_at IS NOT NULL OR used_at IS NOT NULL OR expires_at < :now)
              AND COALESCE(revoked_at, used_at, expires_at) < :cutoff
            ORDER BY id
            LIMIT :batchSize
        )
        """, nativeQuery = true)
    int deleteDeadTokensBatch(
        @Param("now") Instant now,
        @Param("cutoff") Instant cutoff,
        @Param("batchSize") int batchSize);
}
