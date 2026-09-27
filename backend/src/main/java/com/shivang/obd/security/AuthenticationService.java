package com.shivang.obd.security;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.home.OrganizationalHomeRepository;
import com.shivang.obd.authz.home.OrganizationalHomeType;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.identity.CredentialType;
import com.shivang.obd.identity.EmailNormalizer;
import com.shivang.obd.identity.UserCredentialEntity;
import com.shivang.obd.identity.UserCredentialRepository;
import com.shivang.obd.identity.UserEntity;
import com.shivang.obd.identity.UserRepository;
import com.shivang.obd.security.token.TokenService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generic failure message is used for every rejection path (unknown email,
 * wrong password, suspended account) so the endpoint never reveals whether
 * an account exists.
 */
@Service
public class AuthenticationService {

    public static final String GENERIC_FAILURE = "Invalid email or password.";

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final com.shivang.obd.security.token.RefreshTokenService refreshTokenService;
    private final com.shivang.obd.security.ratelimit.LoginProtectionService loginProtection;
    private final com.shivang.obd.security.event.SecurityEventService securityEventService;
    private final AuthorizationService authorizationService;
    private final OrganizationalHomeRepository homeRepository;

    public AuthenticationService(
        UserRepository userRepository,
        UserCredentialRepository credentialRepository,
        PasswordEncoder passwordEncoder,
        TokenService tokenService,
        com.shivang.obd.security.token.RefreshTokenService refreshTokenService,
        com.shivang.obd.security.ratelimit.LoginProtectionService loginProtection,
        com.shivang.obd.security.event.SecurityEventService securityEventService,
        AuthorizationService authorizationService,
        OrganizationalHomeRepository homeRepository
    ) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.loginProtection = loginProtection;
        this.securityEventService = securityEventService;
        this.authorizationService = authorizationService;
        this.homeRepository = homeRepository;
    }

    @Transactional
    public AuthSessionResult login(LoginRequest request) {
        String normalizedEmail = EmailNormalizer.canonicalize(request.email());

        loginProtection.checkCurrentRequestAllowed(normalizedEmail);
        try {
            AuthSessionResult response = authenticateAndIssue(normalizedEmail, request.password());
            loginProtection.resetForCurrentRequestSuccess(normalizedEmail);
            return response;
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == CommonErrorCode.UNAUTHORIZED) {
                loginProtection.recordCurrentRequestFailure(normalizedEmail);
            }
            throw ex;
        }
    }

    private AuthSessionResult authenticateAndIssue(String normalizedEmail, String password) {
        UserEntity user = userRepository.findByNormalizedEmail(normalizedEmail)
            .filter(u -> u.getDeletedAt() == null)
            .filter(u -> u.getStatus() == LifecycleStatus.ACTIVE)
            .orElseThrow(() -> failWithLoginEvent(null));

        UserCredentialEntity credential = credentialRepository
            .findByUserIdAndIdentityType(user.getId(), CredentialType.PASSWORD)
            .orElseThrow(() -> failWithLoginEvent(user.getId()));

        if (!passwordEncoder.matches(password, credential.getCredentialHash())) {
            throw failWithLoginEvent(user.getId());
        }

        // Session first: its family id becomes the sid claim of the JWT.
        var issuedRefresh = refreshTokenService.issueFor(
            new AuthenticatedUser(user.getId(), user.getEmail(), null));
        AuthenticatedUser principal = new AuthenticatedUser(
            user.getId(), user.getEmail(), issuedRefresh.sessionId());
        TokenService.IssuedToken issued = tokenService.issueAccessToken(principal);
        securityEventService.recordLoginSuccess(user.getId(),
            Map.of("authenticationMethod", "password", "result", "success"));
        return new AuthSessionResult(
            issued.tokenValue(), issuedRefresh.rawRefreshToken(), issued.expiresInSeconds());
    }

    @Transactional
    public AuthSessionResult refresh(String presentedRawRefreshToken) {
        var rotated = refreshTokenService.rotate(presentedRawRefreshToken);
        TokenService.IssuedToken issued = tokenService.issueAccessToken(rotated.user());
        return new AuthSessionResult(issued.tokenValue(), rotated.rawRefreshToken(), issued.expiresInSeconds());
    }

    @Transactional(readOnly = true)
    public AuthenticatedUserResponse me(AuthenticatedUser principal) {
        if (principal == null || principal.userId() == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, GENERIC_FAILURE);
        }
        UUID userId = principal.userId();
        
        // Get home type and organization ID
        final OrganizationalHomeType homeType;
        final UUID organizationId;
        var home = homeRepository.findByUserId(userId);
        if (home.isPresent()) {
            homeType = home.get().getHomeType();
            organizationId = home.get().getOrganizationId();
        } else {
            homeType = null;
            organizationId = null;
        }
        
        // Get all capabilities for the user
        final Set<String> capabilities = authorizationService.getAllCapabilitiesForUser(userId);
        
        return userRepository.findById(userId)
            .map(user -> new AuthenticatedUserResponse(
                user.getId(), user.getEmail(), user.getStatus(), homeType, organizationId, capabilities))
            .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED, GENERIC_FAILURE));
    }

    /**
     * LOGIN_FAILURE uses REQUIRES_NEW so the record survives this method's
     * rollback-inducing throw. Known userId is attached only when the server
     * has already resolved it; unknown-email failures stay userId=null.
     * Metadata is generic by design (no account-state oracle).
     */
    private BusinessException failWithLoginEvent(UUID knownUserIdOrNull) {
        securityEventService.recordLoginFailure(knownUserIdOrNull,
            Map.of("reason", "authentication_failed"));
        return invalidCredentials();
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(CommonErrorCode.UNAUTHORIZED, GENERIC_FAILURE);
    }
}
