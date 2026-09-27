package com.shivang.obd.identity;

import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions an identity: a user plus its PASSWORD credential, stored in
 * the separated credential table so future authentication mechanisms can
 * be added without touching the identity model. Callers remain
 * responsible for authorization and organizational wiring (home,
 * membership, role); this service guarantees canonical-email uniqueness
 * and never persists or logs plaintext passwords.
 */
@Service
public class UserAccountService {

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(
        UserRepository userRepository,
        UserCredentialRepository credentialRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates an ACTIVE user with a PASSWORD credential. Runs within the
     * caller's transaction so provisioning stays atomic end-to-end.
     *
     * @throws ConflictException when the canonical email is already registered
     */
    @Transactional
    public UserEntity createUserWithPassword(
        String email, String plaintextPassword, String displayName
    ) {
        String normalizedEmail = EmailNormalizer.canonicalize(email);
        if (userRepository.existsByNormalizedEmail(normalizedEmail)) {
            throw new ConflictException("A user with this email already exists.");
        }

        var user = new UserEntity();
        user.setEmail(email.trim());
        user.setNormalizedEmail(normalizedEmail);
        user.setDisplayName(displayName);
        user.setStatus(LifecycleStatus.ACTIVE);
        var savedUser = userRepository.save(user);

        var credential = new UserCredentialEntity();
        credential.setUserId(savedUser.getId());
        credential.setIdentityType(CredentialType.PASSWORD);
        credential.setCredentialHash(passwordEncoder.encode(plaintextPassword));
        credentialRepository.save(credential);

        return savedUser;
    }
}
