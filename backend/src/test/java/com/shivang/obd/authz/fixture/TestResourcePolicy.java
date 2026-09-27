package com.shivang.obd.authz.fixture;

import com.shivang.obd.authz.ResourceAuthorizationPolicy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test fixture proving OWN/ASSIGNED resource-policy delegation through the
 * full authorization stack. In-memory only; not a production entity.
 *
 * Each TestResource has:
 * - tenantId (organizational boundary)
 * - ownerId (OWN scope)
 * - assignedUserId (ASSIGNED scope)
 */
public class TestResourcePolicy implements ResourceAuthorizationPolicy {

    public static final String RESOURCE_TYPE = "TEST_RESOURCE";

    public record TestResource(UUID id, UUID tenantId, UUID ownerId, UUID assignedUserId) {
    }

    private final Map<UUID, TestResource> store = new ConcurrentHashMap<>();

    public void put(TestResource resource) {
        store.put(resource.id(), resource);
    }

    public void clear() {
        store.clear();
    }

    @Override
    public boolean supports(String resourceType) {
        return RESOURCE_TYPE.equals(resourceType);
    }

    @Override
    public boolean isAllowed(UUID userId, String capabilityKey, String resourceType, UUID resourceId) {
        var resource = store.get(resourceId);
        if (resource == null) {
            return false;
        }
        return resource.ownerId().equals(userId) || resource.assignedUserId().equals(userId);
    }
}
