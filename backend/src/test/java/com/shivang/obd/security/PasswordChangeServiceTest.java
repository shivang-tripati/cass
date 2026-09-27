package com.shivang.obd.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.identity.CredentialType;
import com.shivang.obd.identity.UserCredentialEntity;
import com.shivang.obd.identity.UserCredentialRepository;
import com.shivang.obd.identity.UserEntity;
import com.shivang.obd.identity.UserRepository;
import com.shivang.obd.security.event.SecurityEventService;
import com.shivang.obd.security.token.RevocationReason;
import com.shivang.obd.security.token.RefreshTokenRevocationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordChangeServiceTest {

    private static final String OLD_PASSWORD = "Old#Passw0rd!123";
    private static final String NEW_PASSWORD = "New#Passw0rd!456";

    private CurrentUserProvider currentUserProvider;
    private UserRepository userRepository;
    private UserCredentialRepository credentialRepository;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenRevocationService revocationService;
    private SecurityEventService securityEventService;
    private PasswordChangeService service;

    private UUID userId;
    private UserCredentialEntity credential;

    @BeforeEach
    void setUp() {
        currentUserProvider = mock(CurrentUserProvider.class);
        userRepository = mock(UserRepository.class);
        credentialRepository = mock(UserCredentialRepository.class);
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        revocationService = mock(RefreshTokenRevocationService.class);
        securityEventService = mock(SecurityEventService.class);

        userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setEmail("user@obd.test");
        user.setStatus(LifecycleStatus.ACTIVE);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(currentUserProvider.current()).thenReturn(Optional.of(
            new AuthenticatedUser(userId, "user@obd.test", UUID.randomUUID())));

        credential = new UserCredentialEntity();
        credential.setUserId(userId);
        credential.setIdentityType(CredentialType.PASSWORD);
        credential.setCredentialHash(passwordEncoder.encode(OLD_PASSWORD));
        when(credentialRepository.findByUserIdAndIdentityType(userId, CredentialType.PASSWORD))
            .thenReturn(Optional.of(credential));

        service = new PasswordChangeService(
            currentUserProvider, userRepository, credentialRepository,
            passwordEncoder, new PasswordPolicy(), revocationService, securityEventService);
    }

    @Test
    void successStoresBcryptHashNeverPlaintextAndRevokesAllSessions() {
        service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD));

        ArgumentCaptor<UserCredentialEntity> captor = ArgumentCaptor.forClass(UserCredentialEntity.class);
        verify(credentialRepository).save(captor.capture());
        String stored = captor.getValue().getCredentialHash();
        assertThat(stored).isNotEqualTo(NEW_PASSWORD);
        assertThat(stored).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches(NEW_PASSWORD, stored)).isTrue();

        verify(revocationService).revokeAllUserSessions(userId, RevocationReason.PASSWORD_CHANGED);
        verify(securityEventService).recordPasswordChanged(org.mockito.ArgumentMatchers.eq(userId),
            org.mockito.ArgumentMatchers.argThat(metadata ->
                "password_changed".equals(metadata.get("reason")) && metadata.size() == 1));

        // Ordering: revocation precedes the success event inside the same TX.
        var inOrder = org.mockito.Mockito.inOrder(revocationService, securityEventService);
        inOrder.verify(revocationService).revokeAllUserSessions(userId, RevocationReason.PASSWORD_CHANGED);
        inOrder.verify(securityEventService).recordPasswordChanged(org.mockito.ArgumentMatchers.eq(userId),
            org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void wrongCurrentPasswordFailsGenericallyWithoutWrites() {
        BusinessException failure = (BusinessException) catchThrowable(
            () -> service.changePassword(new ChangePasswordRequest("Totally#Wrong123", NEW_PASSWORD)));

        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED);
        assertThat(failure.getMessage()).isEqualTo(AuthenticationService.GENERIC_FAILURE);
        verify(credentialRepository, never()).save(any());
        verify(revocationService, never()).revokeAllUserSessions(any(), any());
        verify(securityEventService, never()).recordPasswordChanged(any(), anyMap());
    }

    @Test
    void sameAsCurrentRejected() {
        BusinessException failure = (BusinessException) catchThrowable(
            () -> service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, OLD_PASSWORD)));

        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void tooShortNewPasswordRejectedByPolicy() {
        BusinessException failure = (BusinessException) catchThrowable(
            () -> service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, "short12")));

        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR);
        verify(credentialRepository, never()).save(any());
        verify(revocationService, never()).revokeAllUserSessions(any(), any());
        verify(securityEventService, never()).recordPasswordChanged(any(), anyMap());
    }

    @Test
    void suspendedUserCannotChangePassword() {
        UserEntity suspended = new UserEntity();
        suspended.setId(userId);
        suspended.setStatus(LifecycleStatus.SUSPENDED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(suspended));

        BusinessException failure = (BusinessException) catchThrowable(
            () -> service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD)));

        assertThat(failure.getMessage()).isEqualTo(AuthenticationService.GENERIC_FAILURE);
        verify(credentialRepository, never()).save(any());
        verify(revocationService, never()).revokeAllUserSessions(any(), any());
        verify(securityEventService, never()).recordPasswordChanged(any(), anyMap());
    }

    @Test
    void softDeletedUserCannotChangePassword() {
        UserEntity deleted = new UserEntity();
        deleted.setId(userId);
        deleted.setStatus(LifecycleStatus.ACTIVE);
        deleted.setDeletedAt(java.time.Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(deleted));

        BusinessException failure = (BusinessException) catchThrowable(
            () -> service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD)));

        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED);
        assertThat(failure.getMessage()).isEqualTo(AuthenticationService.GENERIC_FAILURE);
        verify(credentialRepository, never()).save(any());
        verify(revocationService, never()).revokeAllUserSessions(any(), any());
        verify(securityEventService, never()).recordPasswordChanged(any(), anyMap());
    }

    @Test
    void unauthenticatedCallerRejected() {
        when(currentUserProvider.current()).thenReturn(Optional.empty());

        BusinessException failure = (BusinessException) catchThrowable(
            () -> service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD)));

        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED);
    }

    @Test
    void revocationTargetsOnlyTheAuthenticatedUserId() {
        service.changePassword(new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD));

        verify(revocationService).revokeAllUserSessions(
            org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.eq(RevocationReason.PASSWORD_CHANGED));
        verify(revocationService, never()).revokeAllUserSessions(
            org.mockito.ArgumentMatchers.argThat(id -> id != null && !id.equals(userId)), any());
    }
}
