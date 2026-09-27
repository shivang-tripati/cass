package com.shivang.obd.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefreshTokenRevocationServiceTest {

    private RefreshTokenRepository repository;
    private RefreshTokenFamilyRevoker compromisedFamilyRevoker;
    private RefreshTokenHasher hasher;
    private RefreshTokenRevocationService service;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        compromisedFamilyRevoker = mock(RefreshTokenFamilyRevoker.class);
        hasher = new RefreshTokenHasher();
        service = new RefreshTokenRevocationService(
            repository, compromisedFamilyRevoker, hasher);
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

    @Test
    void presentedTokenResolvesItsOwnerAndRevokesTheFamily() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String raw = "cookie-only-logout-presented-token-01";
        when(repository.findByTokenHash(hasher.hash(raw)))
            .thenReturn(Optional.of(token(userId, familyId, raw)));
        when(repository.revokeActiveFamilyForUser(eq(familyId), eq(userId), any(Instant.class), any()))
            .thenReturn(3);

        Optional<UUID> revokedOwner =
            service.revokeByPresentedToken(raw, RevocationReason.LOGOUT);

        assertThat(revokedOwner).contains(userId);
        verify(repository).revokeActiveFamilyForUser(
            eq(familyId), eq(userId), any(Instant.class), eq(RevocationReason.LOGOUT.name()));
    }

    @Test
    void alreadyRevokedTokenIsAnIdempotentNoOp() {
        UUID userId = UUID.randomUUID();
        String raw = "already-revoked-cookie-logout-token-2";
        RefreshTokenEntity revoked = token(userId, UUID.randomUUID(), raw);
        revoked.setRevokedAt(Instant.now().minusSeconds(5));
        when(repository.findByTokenHash(hasher.hash(raw))).thenReturn(Optional.of(revoked));

        assertThat(service.revokeByPresentedToken(raw, RevocationReason.LOGOUT)).isEmpty();
        verifyNoInteractions(compromisedFamilyRevoker);
        verify(repository, never()).revokeActiveFamilyForUser(any(), any(), any(), any());
    }

    @Test
    void unknownTokenIsAnIdempotentNoOp() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThat(service.revokeByPresentedToken(
            "never-issued-token-value-000000x", RevocationReason.LOGOUT_ALL)).isEmpty();
        verify(repository, never()).revokeActiveFamilyForUser(any(), any(), any(), any());
    }

    @Test
    void racingRevocationObservedThroughZeroUpdatedRowsYieldsEmptyOwner() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String raw = "raced-cookie-logout-vs-rotation-tok";
        when(repository.findByTokenHash(hasher.hash(raw)))
            .thenReturn(Optional.of(token(userId, familyId, raw)));
        when(repository.revokeActiveFamilyForUser(eq(familyId), eq(userId), any(Instant.class), any()))
            .thenReturn(0);

        assertThat(service.revokeByPresentedToken(raw, RevocationReason.LOGOUT)).isEmpty();
    }
}
