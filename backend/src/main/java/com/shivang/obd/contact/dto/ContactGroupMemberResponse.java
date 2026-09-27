package com.shivang.obd.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One contact-group membership (VB-6B.2). Exposes the physical
 * relationship row plus the embedded live-contact payload; memberships
 * have no update semantics, so no mutable fields exist.
 */
@Schema(description = "A contact's membership in a contact group, with the "
    + "live contact identity embedded. The membership is a physical, "
    + "immutable relationship row: it has no update semantics and no "
    + "soft delete.")
public record ContactGroupMemberResponse(
    @Schema(description = "Membership row id (the relationship's own identifier).",
        example = "7e6a1c50-4d21-4e9f-9d3a-2b8f5a111001", requiredMode = Schema.RequiredMode.REQUIRED)
    UUID memberId,

    @Schema(description = "Contact group containing the member.", requiredMode = Schema.RequiredMode.REQUIRED)
    UUID groupId,

    @Schema(description = "Tenant-level contact identity participating in the group. "
        + "The same contact may be a member of many groups.",
        requiredMode = Schema.RequiredMode.REQUIRED)
    UUID contactId,

    @Schema(description = "Owning tenant; DB-enforced equal to the group's and the contact's tenant.",
        requiredMode = Schema.RequiredMode.REQUIRED)
    UUID tenantId,

    @Schema(description = "Live contact payload (embedded for roster convenience).")
    ContactResponse contact,

    @Schema(description = "When the membership was created (UTC instant).",
        example = "2026-09-26T10:15:30Z", requiredMode = Schema.RequiredMode.REQUIRED)
    Instant createdAt,

    @Schema(description = "User who created the membership, when known. Rows created by the "
        + "V46 migration carry no value.",
        example = "6d1f2a30-1c14-4a3f-8f2e-5f6a7b8c9d01", nullable = true)
    UUID createdBy
) {
}
