package com.shivang.obd.security.event;

import java.util.Optional;

/**
 * Server-side request metadata for security events. Implementations must
 * derive values exclusively from the server environment (servlet request,
 * MDC) — never from client-asserted headers carrying identity.
 */
public interface SecurityEventRequestContextProvider {

    record RequestMetadata(String requestId, String ipAddress, String userAgent) {
    }

    Optional<RequestMetadata> current();
}
