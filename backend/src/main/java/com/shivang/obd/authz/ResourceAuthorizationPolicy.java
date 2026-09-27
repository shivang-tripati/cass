package com.shivang.obd.authz;

import java.util.UUID;

/**
 * Extension point for resource-level authorization.
 *
 * <p>The generic authorization layer evaluates capabilities against
 * organizational scope (PLATFORM / RESELLER / TENANT). It deliberately
 * knows nothing about future domain entities: policies registered through
 * this interface carry that knowledge instead. A future call module, for
 * example, would expose a policy supporting resource type "CALL" and decide
 * OWN/ASSIGNED questions such as "is this user the assigned agent of the
 * given call". No campaign/call/contact types may be referenced here.</p>
 */
public interface ResourceAuthorizationPolicy {

    boolean supports(String resourceType);

    boolean isAllowed(UUID userId, String capabilityKey, String resourceType, UUID resourceId);
}
