package com.shivang.obd.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.home.OrganizationalHomeRepository;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.identity.CredentialType;
import com.shivang.obd.identity.EmailNormalizer;
import com.shivang.obd.identity.UserCredentialEntity;
import com.shivang.obd.identity.UserCredentialRepository;
import com.shivang.obd.identity.UserEntity;
import com.shivang.obd.identity.UserRepository;
import com.shivang.obd.security.ratelimit.InMemoryLoginRateLimiter;
import com.shivang.obd.security.event.SecurityEventService;
import com.shivang.obd.security.ratelimit.LoginProtectionService;
import com.shivang.obd.security.token.RefreshTokenService;
import com.shivang.obd.security.token.TokenService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthenticationServiceTest {

    private static final String RAW_PASSWORD = "Sup3rSecret!x";
    private static final UUID FAMILY_ID = UUID.fromString("f1f1f1f1-1111-4111-8111-111111111111");

    private UserRepository userRepository;
    private UserCredentialRepository credentialRepository;
    private PasswordEncoder passwordEncoder;
    private TokenService tokenService;
    private RefreshTokenService refreshTokenService;
    private InMemoryLoginRateLimiter limiter;
    private LoginProtectionService loginProtection;
    private SecurityEventService securityEventService;
    private AuthorizationService authorizationService;
    private OrganizationalHomeRepository homeRepository;
    private AuthenticationService service;

@BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        credentialRepository = mock(UserCredentialRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        tokenService = mock(TokenService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        limiter = new InMemoryLoginRateLimiter(10);
        securityEventService = mock(SecurityEventService.class);
        loginProtection = new LoginProtectionService(limiter,
            () -> java.util.Optional.of(new com.shivang.obd.security.event.SecurityEventRequestContextProvider.RequestMetadata("req", "10.0.0.1", "UA")),
            new com.shivang.obd.security.detection.SuspiciousActivityDetector(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        authorizationService = mock(AuthorizationService.class);
        homeRepository = mock(OrganizationalHomeRepository.class);
        
        // Default mock behavior for new dependencies
        when(authorizationService.getAllCapabilitiesForUser(any())).thenReturn(Set.of());
        when(homeRepository.findByUserId(any())).thenReturn(Optional.empty());
        
        service = new AuthenticationService(
            userRepository, credentialRepository, passwordEncoder, tokenService,
            refreshTokenService, loginProtection, securityEventService, authorizationService, homeRepository);
    }

    private UserEntity activeUser(String email) {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setNormalizedEmail(EmailNormalizer.canonicalize(email));
        user.setStatus(LifecycleStatus.ACTIVE);
        return user;
    }

    private void stubPasswordCredential(UUID userId, String rawOrHash) {
        UserCredentialEntity credential = new UserCredentialEntity();
        credential.setUserId(userId);
        credential.setIdentityType(CredentialType.PASSWORD);
        credential.setCredentialHash(rawOrHash);
        when(credentialRepository.findByUserIdAndIdentityType(userId, CredentialType.PASSWORD))
            .thenReturn(Optional.of(credential));
    }

    @Test
    void validCredentialsAuthenticateAndIssueToken() {
        String email = "User@Example.COM";
        UserEntity user = activeUser(email);
        UUID userId = user.getId();
        when(userRepository.findByNormalizedEmail("user@example.com")).thenReturn(Optional.of(user));
        stubPasswordCredential(userId, passwordEncoder.encode(RAW_PASSWORD));
        when(tokenService.issueAccessToken(any())).thenReturn(new TokenService.IssuedToken("jwt-value", 900));
        when(refreshTokenService.issueFor(any()))
            .thenReturn(new com.shivang.obd.security.token.RefreshTokenService.IssuedRefresh(FAMILY_ID, "raw-refresh-value"));

        AuthSessionResult response = service.login(new LoginRequest(email, RAW_PASSWORD));

        assertThat(response.accessToken()).isEqualTo("jwt-value");
        assertThat(response.rawRefreshToken()).isEqualTo("raw-refresh-value");
        assertThat(response.rawRefreshToken()).isNotEqualTo(response.accessToken());
        verify(tokenService).issueAccessToken(any(AuthenticatedUser.class));
    }

    @Test
    void unknownEmailAndWrongPasswordProduceIdenticalGenericError() {
        when(userRepository.findByNormalizedEmail(anyString())).thenReturn(Optional.empty());
        BusinessException unknown = catchLogin("ghost@example.com", "whatever");

        UserEntity user = activeUser("known@example.com");
        when(userRepository.findByNormalizedEmail("known@example.com")).thenReturn(Optional.of(user));
        stubPasswordCredential(user.getId(), passwordEncoder.encode(RAW_PASSWORD));
        BusinessException wrongPassword = catchLogin("known@example.com", "wrong-pass");

        assertThat(unknown.getMessage()).isEqualTo(wrongPassword.getMessage())
            .isEqualTo(AuthenticationService.GENERIC_FAILURE);
        assertThat(unknown.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED);
    }

    @Test
    void suspendedUserIsRejectedWithGenericError() {
        UserEntity suspended = activeUser("suspended@example.com");
        suspended.setStatus(LifecycleStatus.SUSPENDED);
        when(userRepository.findByNormalizedEmail("suspended@example.com"))
            .thenReturn(Optional.of(suspended));

        BusinessException failure = catchLogin("suspended@example.com", RAW_PASSWORD);

        assertThat(failure.getMessage()).isEqualTo(AuthenticationService.GENERIC_FAILURE);
    }

    @Test
    void storedHashIsNeverThePlaintext() {
        UserEntity user = activeUser("hash@example.com");
        when(userRepository.findByNormalizedEmail("hash@example.com")).thenReturn(Optional.of(user));
        stubPasswordCredential(user.getId(), passwordEncoder.encode(RAW_PASSWORD));
        when(tokenService.issueAccessToken(any())).thenReturn(new TokenService.IssuedToken("t", 900));
        when(refreshTokenService.issueFor(any())).thenReturn(
            new com.shivang.obd.security.token.RefreshTokenService.IssuedRefresh(FAMILY_ID, "raw-refresh-value"));

        service.login(new LoginRequest("hash@example.com", RAW_PASSWORD));

        ArgumentCaptor<AuthenticatedUser> captor = ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(tokenService).issueAccessToken(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("hash@example.com");
        assertThat(captor.getValue().userId()).isEqualTo(user.getId());
        assertThat(captor.getValue().sessionId()).isEqualTo(FAMILY_ID);
    }

    @Test
    void rateLimitedLoginReturns429BeforeAnyAuthenticationWork() {
        for (int i = 0; i < 10; i++) {
            loginProtection.recordCurrentRequestFailure("user@example.com");
        }

        BusinessException failure = catchLogin("user@example.com", RAW_PASSWORD);

        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.RATE_LIMITED);
        assertThat(failure.getHttpStatus().value()).isEqualTo(429);
        verifyNoInteractions(userRepository);
        verifyNoInteractions(securityEventService);
    }

    @Test
    void successfulLoginEmitsLoginSuccessWithBoundedMetadata() {
        String email = "evt-success@obd.test";
        UserEntity user = activeUser(email);
        when(userRepository.findByNormalizedEmail(EmailNormalizer.canonicalize(email)))
            .thenReturn(Optional.of(user));
        stubPasswordCredential(user.getId(), passwordEncoder.encode(RAW_PASSWORD));
        when(refreshTokenService.issueFor(any())).thenReturn(
            new com.shivang.obd.security.token.RefreshTokenService.IssuedRefresh(FAMILY_ID, "r"));
        when(tokenService.issueAccessToken(any())).thenReturn(new TokenService.IssuedToken("jwt", 900));

        service.login(new LoginRequest(email, RAW_PASSWORD));

        ArgumentCaptor<UUID> userCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(securityEventService).recordLoginSuccess(userCaptor.capture(),
            org.mockito.ArgumentMatchers.argThat(metadata ->
                "password".equals(metadata.get("authenticationMethod"))
                    && "success".equals(metadata.get("result"))
                    && metadata.size() == 2));
        assertThat(userCaptor.getValue()).isEqualTo(user.getId());
    }

    @Test
    void unknownEmailEmitsLoginFailureWithNullUserId() {
        when(userRepository.findByNormalizedEmail(anyString())).thenReturn(Optional.empty());

        catchLogin("ghost@obd.test", RAW_PASSWORD);

        verify(securityEventService).recordLoginFailure(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.argThat(metadata ->
                "authentication_failed".equals(metadata.get("reason"))));
    }

    @Test
    void wrongPasswordEmitsLoginFailureWithKnownUserId() {
        UserEntity user = activeUser("known@obd.test");
        when(userRepository.findByNormalizedEmail("known@obd.test")).thenReturn(Optional.of(user));
        stubPasswordCredential(user.getId(), passwordEncoder.encode(RAW_PASSWORD));

        catchLogin("known@obd.test", "Definitely#Wrong99");

        verify(securityEventService).recordLoginFailure(
            org.mockito.ArgumentMatchers.eq(user.getId()),
            org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void suspendedUserEmitsLoginFailureWithKnownUserId() {
        UserEntity suspended = activeUser("suspended@obd.test");
        suspended.setStatus(LifecycleStatus.SUSPENDED);
        when(userRepository.findByNormalizedEmail("suspended@obd.test"))
            .thenReturn(Optional.of(suspended));

        catchLogin("suspended@obd.test", RAW_PASSWORD);

        verify(securityEventService).recordLoginFailure(
            org.mockito.ArgumentMatchers.eq(suspended.getId()),
            anyMap());
    }

    @Test
    void softDeletedUserIsRejectedWithGenericError() {
        UserEntity deleted = activeUser("deleted@obd.test");
        deleted.setDeletedAt(java.time.Instant.now());
        when(userRepository.findByNormalizedEmail("deleted@obd.test"))
            .thenReturn(Optional.of(deleted));

        BusinessException failure = catchLogin("deleted@obd.test", RAW_PASSWORD);

        assertThat(failure.getMessage()).isEqualTo(AuthenticationService.GENERIC_FAILURE);
        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED);
    }

    private BusinessException catchLogin(String email, String password) {
        return (BusinessException) org.assertj.core.api.Assertions.catchThrowable(
            () -> service.login(new LoginRequest(email, password)));
    }
    @Test
    void failedLoginRecordsAccountFailureState() {
        UserEntity user = activeUser("failed@obd.test");
        when(userRepository.findByNormalizedEmail("failed@obd.test")).thenReturn(Optional.of(user));
        stubPasswordCredential(user.getId(), passwordEncoder.encode(RAW_PASSWORD));
        when(refreshTokenService.issueFor(any())).thenReturn(
            new com.shivang.obd.security.token.RefreshTokenService.IssuedRefresh(FAMILY_ID, "r"));

        catchLogin("failed@obd.test", "Definitely#Wrong99");

        assertThat(limiter.failureCountOnAccount("failed@obd.test")).isEqualTo(1);
    }

    @Test
    void successfulLoginResetsAccountFailureState() {
        String email = "reset@obd.test";
        UserEntity user = activeUser(email);
        when(userRepository.findByNormalizedEmail(EmailNormalizer.canonicalize(email)))
            .thenReturn(Optional.of(user));
        stubPasswordCredential(user.getId(), passwordEncoder.encode(RAW_PASSWORD));
        when(refreshTokenService.issueFor(any())).thenReturn(
            new com.shivang.obd.security.token.RefreshTokenService.IssuedRefresh(FAMILY_ID, "r"));
        when(tokenService.issueAccessToken(any())).thenReturn(new TokenService.IssuedToken("jwt", 900));

        catchLogin(email, "Wrong#Password1");
        assertThat(limiter.failureCountOnAccount("reset@obd.test")).isEqualTo(1);

        service.login(new LoginRequest(email, RAW_PASSWORD));

        assertThat(limiter.failureCountOnAccount("reset@obd.test")).isZero();
    }
}
