package com.shivang.obd.security.config;

import com.shivang.obd.authz.context.MembershipResolver;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Derives the organizational context from the AUTHENTICATED PRINCIPAL and
 * server-side membership data. Tenant/reseller identifiers arriving via
 * headers, query parameters or request bodies are never trusted for
 * authorization purposes.
 */
public class OrganizationContextPopulationFilter extends OncePerRequestFilter {

    private final List<MembershipResolver> membershipResolvers;

    public OrganizationContextPopulationFilter(List<MembershipResolver> membershipResolvers) {
        this.membershipResolvers = List.copyOf(membershipResolvers);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            resolveAndSetContext(user);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            OrganizationContextHolder.clear();
        }
    }

    private void resolveAndSetContext(AuthenticatedUser user) {
        UUID tenantId = null;
        UUID resellerId = null;
        for (MembershipResolver resolver : membershipResolvers) {
            if (tenantId == null) {
                tenantId = resolver.resolvePrimaryTenantId(user.userId()).orElse(null);
            }
            if (resellerId == null) {
                resellerId = resolver.resolvePrimaryResellerId(user.userId()).orElse(null);
            }
        }
        OrganizationContextHolder.setAuthenticated(user.userId(), tenantId, resellerId);
    }
}
