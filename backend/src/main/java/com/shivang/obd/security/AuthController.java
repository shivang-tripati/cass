package com.shivang.obd.security;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.security.event.SecurityEventService;
import com.shivang.obd.security.token.RefreshTokenRevocationService;
import com.shivang.obd.security.token.RevocationReason;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final CurrentUserProvider currentUserProvider;
    private final RefreshTokenRevocationService revocationService;
    private final PasswordChangeService passwordChangeService;
    private final SecurityEventService securityEventService;
    private final AuthCookieWriter cookieWriter;

    public AuthController(
        AuthenticationService authenticationService,
        CurrentUserProvider currentUserProvider,
        RefreshTokenRevocationService revocationService,
        PasswordChangeService passwordChangeService,
        SecurityEventService securityEventService,
        AuthCookieWriter cookieWriter
    ) {
        this.authenticationService = authenticationService;
        this.currentUserProvider = currentUserProvider;
        this.revocationService = revocationService;
        this.passwordChangeService = passwordChangeService;
        this.securityEventService = securityEventService;
        this.cookieWriter = cookieWriter;
    }

    /** Authenticated principal is the ONLY identity source; no id parameters. */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthenticatedUserResponse>> me() {
        AuthenticatedUser principal = requireAuthenticated();
        return ResponseEntity.ok(
            ResponseFactory.ok(authenticationService.me(principal)));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
        @Valid @RequestBody ChangePasswordRequest request
    ) {
        requireAuthenticated();
        passwordChangeService.changePassword(request);
        return ResponseEntity.ok(ResponseFactory.success());
    }

    /**
     * Issues the access token in the body and the refresh token exclusively
     * as an HttpOnly cookie — the raw refresh token never enters the JSON
     * response, so XSS cannot exfiltrate the long-lived session credential.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletResponse response
    ) {
        AuthSessionResult session = authenticationService.login(request);
        cookieWriter.addRefreshToken(response, session.rawRefreshToken());
        return ResponseEntity.ok(ResponseFactory.ok(toLoginResponse(session)));
    }

    /**
     * Reads the refresh token from the incoming HttpOnly cookie instead of
     * the request body and rotates it: the presented token is consumed and a
     * fresh one is set as a replacement cookie. Missing or invalid cookies
     * surface as the generic 401 ProblemDetail.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
        @CookieValue(name = AuthCookieWriter.REFRESH_COOKIE_NAME, required = false) String refreshToken,
        HttpServletResponse response
    ) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(
                CommonErrorCode.UNAUTHORIZED, RestAuthMessages.AUTHENTICATION_REQUIRED);
        }
        AuthSessionResult session = authenticationService.refresh(refreshToken);
        cookieWriter.addRefreshToken(response, session.rawRefreshToken());
        return ResponseEntity.ok(ResponseFactory.ok(toLoginResponse(session)));
    }

    /**
     * Revokes ONLY the current session (JWT sid family) and always clears
     * the auth cookie. Reachable without a valid access token: an expired
     * browser session must still be able to end itself. Ownership is
     * enforced by the user_id predicate inside the revocation service, so a
     * manipulated or absent sid can never touch another user's session.
     * Legacy tokens without sid fall back to revoking all sessions of the
     * authenticated user (conservative security default). When only the
     * refresh cookie is present, the presented token resolves its own owner
     * for revocation. Idempotent.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
        @CookieValue(name = AuthCookieWriter.REFRESH_COOKIE_NAME, required = false) String refreshToken,
        HttpServletResponse response
    ) {
        Optional<AuthenticatedUser> principal = currentUserProvider.current();
        if (principal.isPresent()) {
            revokeCurrentSession(principal.get());
        } else if (refreshToken != null && !refreshToken.isBlank()) {
            revocationService.revokeByPresentedToken(refreshToken, RevocationReason.LOGOUT)
                .ifPresent(userId -> securityEventService.recordLogout(userId,
                    Map.of("scope", "current_session")));
        }
        cookieWriter.clearRefreshToken(response);
        return ResponseEntity.ok(ResponseFactory.success());
    }

    /**
     * Revokes every active session of the authenticated user. Idempotent.
     * Existing access JWTs remain valid until natural expiry (stateless);
     * all refresh sessions die immediately.
     */
    @PostMapping("/logout-all")
    public ResponseEntity<ApiResponse<Void>> logoutAll(HttpServletResponse response) {
        AuthenticatedUser user = requireAuthenticated();
        revocationService.revokeAllUserSessions(user.userId(), RevocationReason.LOGOUT_ALL);
        securityEventService.recordLogoutAll(user.userId(),
            Map.of("scope", "all_sessions"));
        cookieWriter.clearRefreshToken(response);
        return ResponseEntity.ok(ResponseFactory.success());
    }

    private void revokeCurrentSession(AuthenticatedUser user) {
        if (user.sessionId() == null) {
            revocationService.revokeAllUserSessions(user.userId(), RevocationReason.LOGOUT);
        } else {
            revocationService.revokeFamilyForUser(
                user.userId(), user.sessionId(), RevocationReason.LOGOUT);
        }
        securityEventService.recordLogout(user.userId(),
            Map.of("scope", "current_session"));
    }

    private LoginResponse toLoginResponse(AuthSessionResult session) {
        return new LoginResponse(session.accessToken(), "Bearer", session.expiresInSeconds());
    }

    private AuthenticatedUser requireAuthenticated() {
        return currentUserProvider.current()
            .orElseThrow(() -> new BusinessException(
                CommonErrorCode.UNAUTHORIZED, RestAuthMessages.AUTHENTICATION_REQUIRED));
    }
}

final class RestAuthMessages {
    private RestAuthMessages() {
    }

    static final String AUTHENTICATION_REQUIRED = "Authentication required.";
}
