package com.shivang.obd.authz.context;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the request-scoped organizational authorization context.
 *
 * <p>The context is SERVER-DERIVED: it may only be populated by trusted
 * application code (the upcoming authentication layer) after the identity
 * and its memberships have been resolved from the database. Tenant or
 * reseller identifiers arriving in request headers/parameters are never
 * authoritative for authorization decisions.</p>
 *
 * <p>{@link OrganizationContextFilter} guarantees the ThreadLocal is cleared
 * at the end of every request, so no state leaks between requests. Absence
 * of a context is represented safely as an empty {@link Optional}.</p>
 */
public final class OrganizationContextHolder {

    private static final ThreadLocal<OrganizationContext> CURRENT = new ThreadLocal<>();

    private OrganizationContextHolder() {
    }

    /**
     * Populates the context for the current request. Intended exclusively
     * for the future authentication layer after server-side resolution.
     */
    public static void setAuthenticated(UUID userId, UUID tenantId, UUID resellerId) {
        CURRENT.set(new OrganizationContext(userId, tenantId, resellerId));
    }

    public static Optional<OrganizationContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Optional<UUID> currentUserId() {
        return current().map(OrganizationContext::userId);
    }

    public static Optional<UUID> currentTenantId() {
        return current().map(OrganizationContext::tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
