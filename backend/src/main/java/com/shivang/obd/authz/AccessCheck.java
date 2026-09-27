package com.shivang.obd.authz;

import java.util.UUID;

public record AccessCheck(UUID resellerId, UUID tenantId) {

    public static AccessCheck platformWide() {
        return new AccessCheck(null, null);
    }

    public static AccessCheck forReseller(UUID resellerId) {
        return new AccessCheck(resellerId, null);
    }

    public static AccessCheck forTenant(UUID tenantId) {
        return new AccessCheck(null, tenantId);
    }
}
