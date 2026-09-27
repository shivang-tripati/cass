package com.shivang.obd.security;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.identity.CredentialType;
import com.shivang.obd.identity.UserCredentialEntity;
import com.shivang.obd.identity.UserCredentialRepository;
import com.shivang.obd.identity.UserRepository;
import com.shivang.obd.security.event.SecurityEventService;
import com.shivang.obd.security.token.RevocationReason;
import com.shivang.obd.security.token.RefreshTokenRevocationService;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates credential verification/update (identity-owned storage) with
 * session revocation (security-owned lifecycle) inside ONE transaction:
 * either the new hash and the PASSWORD_CHANGED revocation commit together,
 * or nothing changes. Failure paths throw before any write.
 *
 * <p>Concurrent double-change: last writer wins on the single hash column
 * (both writes are valid bcrypt hashes); sessions end revoked either way.
 * No corruption possible; optimistic locking deferred until needed.</p>
 */
@Service
public class PasswordChangeService {

    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RefreshTokenRevocationService revocationService;
    private final SecurityEventService securityEventService;

    public PasswordChangeService(
        CurrentUserProvider currentUserProvider,
        UserRepository userRepository,
        UserCredentialRepository credentialRepository,
        PasswordEncoder passwordEncoder,
        PasswordPolicy passwordPolicy,
        RefreshTokenRevocationService revocationService,
        SecurityEventService securityEventService
    ) {
        this.currentUserProvider = currentUserProvider;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.revocationService = revocationService;
        this.securityEventService = securityEventService;
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        AuthenticatedUser principal = currentUserProvider.current()
            .orElseThrow(() -> unauthorized(AuthenticationService.GENERIC_FAILURE));

        var user = userRepository.findById(principal.userId())
            .filter(u -> u.getDeletedAt() == null)
            .filter(u -> u.getStatus() == LifecycleStatus.ACTIVE)
            .orElseThrow(() -> unauthorized(AuthenticationService.GENERIC_FAILURE));

        UserCredentialEntity credential = credentialRepository
            .findByUserIdAndIdentityType(user.getId(), CredentialType.PASSWORD)
            .orElseThrow(() -> unauthorized(AuthenticationService.GENERIC_FAILURE));

        if (!passwordEncoder.matches(request.currentPassword(), credential.getCredentialHash())) {
            throw unauthorized(AuthenticationService.GENERIC_FAILURE);
        }

        passwordPolicy.validateNewPassword(request.newPassword());

        if (passwordEncoder.matches(request.newPassword(), credential.getCredentialHash())) {
            throw new BusinessException(
                CommonErrorCode.BUSINESS_RULE_VIOLATION,
                "New password must differ from the current password.");
        }

        credential.setCredentialHash(passwordEncoder.encode(request.newPassword()));
        credentialRepository.save(credential);

        revocationService.revokeAllUserSessions(user.getId(), RevocationReason.PASSWORD_CHANGED);

        // Same transaction: a rollback of the password update also removes
        // the success event, keeping both sides consistent.
        securityEventService.recordPasswordChanged(user.getId(),
            Map.of("reason", "password_changed"));
    }

    private static BusinessException unauthorized(String message) {
        return new BusinessException(CommonErrorCode.UNAUTHORIZED, message);
    }
}
