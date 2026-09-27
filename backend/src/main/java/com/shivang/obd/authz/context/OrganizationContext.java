package com.shivang.obd.authz.context;

import java.util.UUID;

public record OrganizationContext(UUID userId, UUID tenantId, UUID resellerId) {
}
