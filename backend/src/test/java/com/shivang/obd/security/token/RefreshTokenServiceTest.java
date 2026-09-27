package com.shivang.obd.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.identity.UserEntity;
import com.shivang.obd.identity.UserRepository;
import com.shivang.obd.security.event.SecurityEventService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RefreshTokenServiceTest {

    private static final String SECRET = "unit-test-secret-key-that-is-long-enough-32!";

    private RefreshTokenRepository repository;
    private UserRepository userRepository;
    private RefreshTokenHasher hasher;
    private RefreshTokenRevocationService revocationService;
    private RefreshTokenFamilyRevoker compromisedFamilyRevoker;
    private SecurityEventService securityEventService;
    private SecurityResponseService securityResponseService;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        userRepository = mock(UserRepository.class);
        hasher = new RefreshTokenHasher();
        var properties = new JwtProperties(SECRET, "unit-test", 900, 604800);
        revocationService = mock(RefreshTokenRevocationService.class);
        securityEventService = mock(SecurityEventService.class);
        compromisedFamilyRevoker = mock(RefreshTokenFamilyRevoker.class);
        securityResponseService = mock(SecurityResponseService.class);
        service = new RefreshTokenService(repository, hasher, userRepository, properties,
            revocationService, securityEventService,
            new com.shivang.obd.security.detection.SuspiciousActivityDetector(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            securityResponseService);
        when(repository.save(any(RefreshTokenEntity.class)))
            .thenAnswer(inv -> {
                RefreshTokenEntity entity = inv.getArgument(0);
                if (entity.getId() == null) {
                    entity.setId(UUID.randomUUID());
                }
                return entity;
            });
    }

    private UserEntity activeUser(UUID id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setEmail("user@obd.test");
        user.setNormalizedEmail("user@obd.test");
        user.setStatus(LifecycleStatus.ACTIVE);
        return user;
    }

    private RefreshTokenEntity token(UUID userId, UUID familyId, String raw) {
        RefreshTokenEntity t = new RefreshTokenEntity();
        t.setId(UUID.randomUUID());
        t.setUserId(userId);
        t.setFamilyId(familyId);
        t.setTokenHash(hasher.hash(raw));
        t.setIssuedAt(Instant.now().minusSeconds(60));
        t.setExpiresAt(Instant.now().plusSeconds(600));
        t.setCreatedAt(Instant.now().minusSeconds(60));
        return t;
    }

    private BusinessException catchRotate(String raw) {
        return (BusinessException) catchThrowable(() -> service.rotate(raw));
    }

    @Test
    void issuancePersistsHashNeverRawToken() {
        var issued = service.issueFor(
            new com.shivang.obd.security.AuthenticatedUser(UUID.randomUUID(), "u@obd.test", null));

        ArgumentCaptor<RefreshTokenEntity> captor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(repository).save(captor.capture());
        RefreshTokenEntity saved = captor.getValue();
        assertThat(saved.getTokenHash()).isEqualTo(hasher.hash(issued.rawRefreshToken()));
        assertThat(saved.getTokenHash()).isNotEqualTo(issued.rawRefreshToken());
        assertThat(saved.getFamilyId()).isEqualTo(issued.sessionId());
        assertThat(issued.rawRefreshToken().length()).isGreaterThanOrEqualTo(40);
    }

    @Test
    void rotationSucceedsSameFamilyNewTokens() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String raw1 = "raw-parent-token-value-1234567890";
        RefreshTokenEntity parent = token(userId, familyId, raw1);
        when(repository.findByTokenHash(parent.getTokenHash())).thenReturn(Optional.of(parent));
        when(repository.claimForRotation(org.mockito.ArgumentMatchers.eq(parent.getId()), any(Instant.class))).thenReturn(1);
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
        doNothing().when(securityResponseService).revokeCompromisedFamily(any());
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));

        var rotated = service.rotate(raw1);

        assertThat(rotated.user().userId()).isEqualTo(userId);
        assertThat(rotated.sessionId()).isEqualTo(familyId);
        assertThat(rotated.rawRefreshToken()).isNotEqualTo(raw1);
        verify(securityEventService).recordTokenRefresh(org.mockito.ArgumentMatchers.eq(userId),
            org.mockito.ArgumentMatchers.argThat(metadata ->
                "success".equals(metadata.get("result")) && metadata.size() == 1));
        ArgumentCaptor<RefreshTokenEntity> childCaptor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(childCaptor.capture());
        RefreshTokenEntity child = childCaptor.getAllValues().get(0);
        assertThat(child.getFamilyId()).isEqualTo(familyId);
        assertThat(child.getTokenHash()).isEqualTo(hasher.hash(rotated.rawRefreshToken()));
    }

    @Test
    void rotationClaimsOldTokenAndLinksReplacement() {
        UUID userId = UUID.randomUUID();
        String raw1 = "raw-parent-token-value-9876543210";
        RefreshTokenEntity parent = token(userId, UUID.randomUUID(), raw1);
        when(repository.findByTokenHash(parent.getTokenHash())).thenReturn(Optional.of(parent));
        when(repository.claimForRotation(any(), any())).thenReturn(1);
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));

        var rotated = service.rotate(raw1);

        verify(repository).claimForRotation(org.mockito.ArgumentMatchers.eq(parent.getId()), any());
        ArgumentCaptor<RefreshTokenEntity> parentCaptor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(parentCaptor.capture());
        RefreshTokenEntity savedParent = parentCaptor.getValue();
        assertThat(savedParent.getReplacedBy()).isNotNull();
        assertThat(savedParent.getUsedAt()).isNotNull();
        assertThat(rotated.rawRefreshToken()).isNotBlank();
    }

    @Test
    void reuseOfUsedTokenRevokesEntireFamily() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String rawUsed = "already-used-refresh-token-abcdef";
        RefreshTokenEntity used = token(userId, familyId, rawUsed);
        used.setUsedAt(Instant.now().minusSeconds(30));
        when(repository.findByTokenHash(used.getTokenHash())).thenReturn(Optional.of(used));

        BusinessException failure = catchRotate(rawUsed);

        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED);
        // Ordering: event recorded BEFORE the compromise-safe revocation.
        var inOrder = org.mockito.Mockito.inOrder(securityEventService, securityResponseService);
        inOrder.verify(securityEventService).recordTokenReuseDetected(userId);
        inOrder.verify(securityResponseService).revokeCompromisedFamily(familyId);
    }

    @Test
    void concurrentLoserIsTreatedAsReuseAndRevokesFamily() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String raw = "raced-token-two-simultaneous-requests";
        RefreshTokenEntity token = token(userId, familyId, raw);
        when(repository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
        when(repository.claimForRotation(any(), any())).thenReturn(0);

        BusinessException failure = catchRotate(raw);

        assertThat(failure.getMessage()).isEqualTo(com.shivang.obd.security.AuthenticationService.GENERIC_FAILURE);
        verify(securityResponseService).revokeCompromisedFamily(familyId);
    }

    @Test
    void expiredTokenRejectedWithoutFamilyRevocation() {
        UUID userId = UUID.randomUUID();
        String raw = "expired-but-unused-refresh-token-000";
        RefreshTokenEntity expired = token(userId, UUID.randomUUID(), raw);
        expired.setExpiresAt(Instant.now().minusSeconds(120));
        when(repository.findByTokenHash(expired.getTokenHash())).thenReturn(Optional.of(expired));

        BusinessException failure = catchRotate(raw);

        assertThat(failure.getMessage()).isEqualTo(com.shivang.obd.security.AuthenticationService.GENERIC_FAILURE);
        verify(securityResponseService, never()).revokeCompromisedFamily(any());
        verify(repository, never()).claimForRotation(any(), any());
    }

    @Test
    void revokedTokenRejectedSilently() {
        UUID userId = UUID.randomUUID();
        String raw = "revoked-refresh-token-value-1234567";
        RefreshTokenEntity revoked = token(userId, UUID.randomUUID(), raw);
        revoked.setRevokedAt(Instant.now().minusSeconds(10));
        when(repository.findByTokenHash(revoked.getTokenHash())).thenReturn(Optional.of(revoked));

        BusinessException failure = catchRotate(raw);

        assertThat(failure.getMessage()).isEqualTo(com.shivang.obd.security.AuthenticationService.GENERIC_FAILURE);
        verify(repository, never()).claimForRotation(any(), any());
    }

    @Test
    void unknownHashRejected() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThat(catchRotate("never-seen-token").getErrorCode())
            .isEqualTo(CommonErrorCode.UNAUTHORIZED);
    }

    @Test
    void suspendedUserCannotRefresh() {
        UUID userId = UUID.randomUUID();
        String raw = "suspended-user-refresh-token-1234";
        RefreshTokenEntity token = token(userId, UUID.randomUUID(), raw);
        when(repository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));
        UserEntity suspended = activeUser(userId);
        suspended.setStatus(LifecycleStatus.SUSPENDED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(suspended));

        BusinessException failure = catchRotate(raw);

        assertThat(failure.getMessage()).isEqualTo(com.shivang.obd.security.AuthenticationService.GENERIC_FAILURE);
        verify(repository, never()).claimForRotation(any(), any());
    }

    @Test
    void softDeletedUserCannotRefresh() {
        UUID userId = UUID.randomUUID();
        String raw = "deleted-user-refresh-token-1234";
        RefreshTokenEntity token = token(userId, UUID.randomUUID(), raw);
        when(repository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));
        UserEntity deleted = activeUser(userId);
        deleted.setDeletedAt(Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(deleted));

        BusinessException failure = catchRotate(raw);

        assertThat(failure.getMessage()).isEqualTo(com.shivang.obd.security.AuthenticationService.GENERIC_FAILURE);
        verify(repository, never()).claimForRotation(any(), any());
    }

    @Test
    void everyFailureSharesTheSameGenericMessage() {
        String generic = com.shivang.obd.security.AuthenticationService.GENERIC_FAILURE;

        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThat(catchRotate("a").getMessage()).isEqualTo(generic);

        UUID uid = UUID.randomUUID(); UUID fid = UUID.randomUUID();
        RefreshTokenEntity used = token(uid, fid, "b"); used.setUsedAt(Instant.now());
        when(repository.findByTokenHash(used.getTokenHash())).thenReturn(Optional.of(used));
        assertThat(catchRotate("b").getMessage()).isEqualTo(generic);

        RefreshTokenEntity exp = token(uid, fid, "c"); exp.setExpiresAt(Instant.now().minusSeconds(1));
        when(repository.findByTokenHash(exp.getTokenHash())).thenReturn(Optional.of(exp));
        assertThat(catchRotate("c").getMessage()).isEqualTo(generic);
    }
}
