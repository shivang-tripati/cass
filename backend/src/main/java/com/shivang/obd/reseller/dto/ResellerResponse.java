package com.shivang.obd.reseller.dto;

import com.shivang.obd.common.lifecycle.LifecycleStatus;
import java.time.Instant;
import java.util.UUID;

public record ResellerResponse(
    UUID id,
    String name,
    String slug,
    String displayName,
    LifecycleStatus status,
    String supportEmail,
    String logoUrl,
    String primaryColor,
    Instant createdAt,
    Instant updatedAt
) {
}
