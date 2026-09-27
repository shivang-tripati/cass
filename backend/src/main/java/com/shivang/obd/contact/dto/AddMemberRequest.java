package com.shivang.obd.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for adding an existing contact to a group. The member API
 * never creates contacts — the referenced contact must already be a live
 * identity of the group's tenant.
 */
@Schema(description = "Add an existing contact to a contact group. The contact must be a "
    + "live identity of the group's tenant; the API never creates contacts.")
public record AddMemberRequest(
    @NotNull
    @Schema(description = "Id of the live tenant-level contact to add as a member.",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "3f2b8c40-9e11-4f7a-b6c2-8d3e5a901234")
    UUID contactId
) {
}
