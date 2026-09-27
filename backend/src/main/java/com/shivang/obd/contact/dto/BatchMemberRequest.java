package com.shivang.obd.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Request body for batch add/remove of group members. Items are
 * processed independently — one invalid contact never aborts the others.
 */
@Schema(description = "Batch add/remove of contact-group members. Every listed contact "
    + "receives an independent per-item outcome; a bad item never aborts the rest.")
public record BatchMemberRequest(
    @NotNull
    @NotEmpty
    @Size(max = 500)
    @Schema(description = "Ids of the contacts to add (or remove). A duplicate id inside "
        + "this list is collapsed to a single deterministic outcome and never creates "
        + "duplicate membership rows. Between 1 and 500 items.",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "[\"3f2b8c40-9e11-4f7a-b6c2-8d3e5a901234\", \"5a1d9e70-2c33-4b8f-9e0a-7c6b5d401235\"]")
    List<UUID> contactIds
) {
}
