package com.shivang.obd.security.token;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.identity.UserRepository;
import com.shivang.obd.security.AuthenticationService;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.detection.SecuritySignal;
import com.shivang.obd.security.detection.SecuritySignalSeverity;
import com.shivang.obd.security.detection.SecuritySignalType;
import com.shivang.obd.security.detection.SuspiciousActivityDetector;
import com.shivang.obd.security.event.SecurityEventService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque refresh-token lifecycle: issuance, rotation with family tracking,
 * and reuse detection.
 *
 * <p>Concurrency: rotation claims the presented token through an atomic
 * conditional UPDATE (used_at IS NULL AND revoked_at IS NULL). Exactly one
 * racing request transitions the row; every loser observes zero affected
 * rows and is handled as reuse. No select-then-act race exists.</p>
 *
 * <p>Reuse detection: presenting an already-used token means replay or
 * theft; in both cases the entire family is revoked so every descendant
 * becomes unusable. All failures surface as one generic error.</p>
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHasher hasher;
    private final UserRepository userRepository;
    private final JwtProperties properties;
    private final RefreshTokenRevocationService revocationService;
    private final SecurityEventService securityEventService;
    private final SuspiciousActivityDetector suspiciousActivityDetector;
    private final SecurityResponseService securityResponseService;

    public record RotatedSession(AuthenticatedUser user, UUID sessionId, String rawRefreshToken) {
    }

    public record IssuedRefresh(UUID sessionId, String rawRefreshToken) {
    }

    private record CreatedToken(String raw, RefreshTokenEntity entity) {
    }

    public RefreshTokenService(
        RefreshTokenRepository refreshTokenRepository,
        RefreshTokenHasher hasher,
        UserRepository userRepository,
        JwtProperties properties,
        RefreshTokenRevocationService revocationService,
        SecurityEventService securityEventService,
        SuspiciousActivityDetector suspiciousActivityDetector,
        SecurityResponseService securityResponseService
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.hasher = hasher;
        this.userRepository = userRepository;
        this.properties = properties;
        this.revocationService = revocationService;
        this.securityEventService = securityEventService;
        this.suspiciousActivityDetector = suspiciousActivityDetector;
        this.securityResponseService = securityResponseService;
    }

    /** Creates a new session (token family) for the user. */
    @Transactional
    public IssuedRefresh issueFor(AuthenticatedUser user) {
        CreatedToken created = createToken(user.userId(), UUID.randomUUID());
        return new IssuedRefresh(created.entity().getFamilyId(), created.raw());
    }

    @Transactional
    public RotatedSession rotate(String presentedRawToken) {
        RefreshTokenEntity token = findByHash(presentedRawToken);
        Instant now = Instant.now();

        if (token.getRevokedAt() != null) {
            throw genericFailure();
        }
        if (token.getUsedAt() != null) {
            // Order: detect -> record (independent TX) -> revoke family ->
            // generic failure. The event and revocation both survive the
            // caller-visible rollback.
            suspiciousActivityDetector.process(SecuritySignal.of(
                SecuritySignalType.TOKEN_REUSE, SecuritySignalSeverity.CRITICAL,
                token.getUserId(), token.getFamilyId()));
            securityEventService.recordTokenReuseDetected(token.getUserId());
            securityResponseService.revokeCompromisedFamily(token.getFamilyId());
            throw genericFailure();
        }
        if (token.getExpiresAt().isBefore(now)) {
            throw genericFailure();
        }

        var user = userRepository.findById(token.getUserId())
            .filter(u -> u.getDeletedAt() == null)
            .filter(u -> u.getStatus() == LifecycleStatus.ACTIVE)
            .orElseThrow(RefreshTokenService::genericFailure);

        if (refreshTokenRepository.claimForRotation(token.getId(), now) == 0) {
            suspiciousActivityDetector.process(SecuritySignal.of(
                SecuritySignalType.TOKEN_REUSE, SecuritySignalSeverity.CRITICAL,
                token.getUserId(), token.getFamilyId()));
            securityEventService.recordTokenReuseDetected(token.getUserId());
            securityResponseService.revokeCompromisedFamily(token.getFamilyId());
            throw genericFailure();
        }

        // Mirror the claimed state into the persistence context: without
        // this, the full-entity merge below would overwrite used_at back
        // to NULL and silently disable reuse detection.
        token.setUsedAt(now);

        CreatedToken child = createToken(token.getUserId(), token.getFamilyId());
        token.setReplacedBy(child.entity().getId());
        token.setUpdatedAt(now);
        refreshTokenRepository.save(token);

        securityEventService.recordTokenRefresh(token.getUserId(),
            Map.of("result", "success"));
        return new RotatedSession(
            new AuthenticatedUser(user.getId(), user.getEmail(), token.getFamilyId()),
            token.getFamilyId(),
            child.raw());
    }

    private RefreshTokenEntity findByHash(String rawToken) {
        return refreshTokenRepository.findByTokenHash(hasher.hash(rawToken))
            .orElseThrow(RefreshTokenService::genericFailure);
    }

    private CreatedToken createToken(UUID userId, UUID familyId) {
        String raw = hasher.generateRawToken();
        Instant now = Instant.now();
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUserId(userId);
        entity.setFamilyId(familyId);
        entity.setTokenHash(hasher.hash(raw));
        entity.setIssuedAt(now);
        entity.setExpiresAt(now.plusSeconds(properties.refreshTokenTtlSeconds()));
        entity.setCreatedAt(now);
        return new CreatedToken(raw, refreshTokenRepository.save(entity));
    }

    private static BusinessException genericFailure() {
        return new BusinessException(CommonErrorCode.UNAUTHORIZED, AuthenticationService.GENERIC_FAILURE);
    }
}
