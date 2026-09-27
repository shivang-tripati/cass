package com.shivang.obd.identity;

import com.shivang.obd.authz.PlatformRoleAssignmentEntity;
import com.shivang.obd.authz.PlatformRoleAssignmentRepository;
import com.shivang.obd.authz.RoleEntity;
import com.shivang.obd.authz.RoleRepository;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent bootstrap of the first SUPER_ADMIN so the platform is
 * manually operable before any reseller/tenant exists. SUPER_ADMIN owns no
 * organizational home and no membership: authorization flows purely
 * through the PLATFORM-scoped role.
 *
 * <p>Runs as a system process: the audit actor resolves to SYSTEM, never
 * to a fabricated user id. Credentials come exclusively from configuration
 * (environment); they are never logged and never returned by any API.</p>
 */
@Configuration
@EnableConfigurationProperties(BootstrapProperties.class)
public class SuperAdminBootstrapper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrapper.class);

    static final String SUPER_ADMIN_ROLE_KEY = "SUPER_ADMIN";

    private final BootstrapProperties properties;
    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final PlatformRoleAssignmentRepository platformRoleAssignments;
    private final com.shivang.obd.authz.RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public SuperAdminBootstrapper(
        BootstrapProperties properties,
        UserRepository userRepository,
        UserCredentialRepository credentialRepository,
        PlatformRoleAssignmentRepository platformRoleAssignments,
        com.shivang.obd.authz.RoleRepository roleRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.platformRoleAssignments = platformRoleAssignments;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            log.info("SUPER_ADMIN bootstrap skipped: no credentials configured.");
            return;
        }
        String normalizedEmail = EmailNormalizer.canonicalize(properties.email());
        var existing = userRepository.findByNormalizedEmail(normalizedEmail).orElse(null);
        if (existing != null) {
            // Self-heal accounts created before platform-role bindings existed.
            ensurePlatformRoleBinding(existing.getId());
            log.info("SUPER_ADMIN bootstrap skipped: bootstrap account already exists.");
            return;
        }
        validatePasswordOrThrow();

        var user = new UserEntity();
        user.setEmail(properties.email().trim());
        user.setNormalizedEmail(normalizedEmail);
        user.setDisplayName(properties.displayName());
        user.setStatus(LifecycleStatus.ACTIVE);
        var savedUser = userRepository.save(user);

        var credential = new UserCredentialEntity();
        credential.setUserId(savedUser.getId());
        credential.setIdentityType(CredentialType.PASSWORD);
        credential.setCredentialHash(passwordEncoder.encode(properties.password()));
        credentialRepository.save(credential);

        // SUPER_ADMIN authority is PLATFORM-role-based: bind the bootstrap
        // account to the seeded SUPER_ADMIN role explicitly.
        ensurePlatformRoleBinding(savedUser.getId());

        log.info("Bootstrap SUPER_ADMIN created for {}.", normalizedEmail);
    }

    private void ensurePlatformRoleBinding(java.util.UUID userId) {
        if (platformRoleAssignments.existsByUserId(userId)) {
            return;
        }
        var superAdminRole = roleRepository.findByKeyIgnoreCase(SUPER_ADMIN_ROLE_KEY)
            .filter(com.shivang.obd.authz.RoleEntity::isActive)
            .orElseThrow(() -> new IllegalStateException(
                "System role SUPER_ADMIN is missing; run migrations."));
        var assignment = new PlatformRoleAssignmentEntity();
        assignment.setUserId(userId);
        assignment.setRoleId(superAdminRole.getId());
        assignment.setCreatedAt(java.time.Instant.now());
        platformRoleAssignments.save(assignment);
    }

    private void validatePasswordOrThrow() {
        int minLength = 12;
        if (properties.password().length() < minLength) {
            throw new IllegalStateException(
                "Configured bootstrap SUPER_ADMIN password does not satisfy the platform "
                    + "password policy (minimum " + minLength + " characters).");
        }
    }
}
