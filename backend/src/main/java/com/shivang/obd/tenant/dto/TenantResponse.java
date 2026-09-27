package com.shivang.obd.tenant.dto;

import com.shivang.obd.common.lifecycle.LifecycleStatus;
import java.time.Instant;
import java.util.UUID;

public record TenantResponse(
    UUID id,
    String name,
    String slug,
    LifecycleStatus status,
    UUID resellerId,
    Instant createdAt,
    Instant updatedAt
) {
}
