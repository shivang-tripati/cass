package com.shivang.obd.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A tenant-owned contact group (audience container). Campaigns "
    + "reference groups by identifier; the group's audience is its live memberships.")
public record ContactGroupResponse(
    @Schema(description = "Group id.", requiredMode = Schema.RequiredMode.REQUIRED)
    UUID id,

    @Schema(description = "Owning tenant.", requiredMode = Schema.RequiredMode.REQUIRED)
    UUID tenantId,

    @Schema(description = "Group display name (max 150 characters).",
        requiredMode = Schema.RequiredMode.REQUIRED, example = "September outbound list")
    String name,

    @Schema(description = "Optional free-text description (max 5000 characters).",
        nullable = true, example = "Leads captured at the September trade show")
    String description,

    @Schema(description = "Number of live contacts currently in the group. Memberships of "
        + "soft-deleted contacts are not counted.",
        requiredMode = Schema.RequiredMode.REQUIRED, example = "42")
    long memberCount,

    @Schema(description = "When the group was created (UTC instant).",
        example = "2026-09-01T08:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    Instant createdAt,

    @Schema(description = "When the group was last updated (UTC instant); null if never modified.",
        nullable = true, example = "2026-09-12T14:30:00Z")
    Instant updatedAt
) {
}
