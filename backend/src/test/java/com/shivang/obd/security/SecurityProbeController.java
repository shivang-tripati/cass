package com.shivang.obd.security;

import com.shivang.obd.authz.context.OrganizationContextHolder;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only route probe. Profile-gated so it never collides with the real
 * AuthController during full-application-context tests.
 */
@RestController
@Profile("security-web-slice")
public class SecurityProbeController {

    private final CurrentUserProvider currentUserProvider;

    public SecurityProbeController(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/security-probe")
    public String probe() {
        return "userId=" + currentUserProvider.current().map(AuthenticatedUser::userId).orElse(null)
            + ";sessionId=" + currentUserProvider.current().map(AuthenticatedUser::sessionId).orElse(null)
            + ";tenantId=" + OrganizationContextHolder.currentTenantId().orElse(null);
    }

    @PostMapping("/api/v1/auth/refresh")
    public String refreshRouteProbe() {
        return "refresh-route-reachable";
    }

    @PostMapping("/api/v1/auth/logout")
    public String logoutRouteProbe() {
        return "logout-route-reachable";
    }

    @GetMapping("/api/v1/auth/me")
    public String meRouteProbe() {
        var user = currentUserProvider.current();
        return "email=" + user.map(AuthenticatedUser::email).orElse(null)
            + ";userId=" + user.map(AuthenticatedUser::userId).orElse(null);
    }
}
