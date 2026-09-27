package com.shivang.obd.security;

import com.shivang.obd.common.audit.AuditorIdentitySource;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Represents the authenticated human user as the audit actor. Background
 * processes and anonymous requests resolve no actor here, letting the
 * common audit foundation fall back to SYSTEM.
 */
@Component
public class SecurityAuditorIdentitySource implements AuditorIdentitySource {

    @Override
    public Optional<String> currentActor() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
            .filter(Authentication::isAuthenticated)
            .map(Authentication::getPrincipal)
            .filter(AuthenticatedUser.class::isInstance)
            .map(AuthenticatedUser.class::cast)
            .map(user -> user.userId().toString());
    }
}
