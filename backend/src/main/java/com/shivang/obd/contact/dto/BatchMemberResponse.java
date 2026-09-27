package com.shivang.obd.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Aggregate result of a batch member add/remove: one result per
 * requested item, in request order (duplicates collapsed).
 */
@Schema(description = "Batch member operation result: exactly one entry per distinct "
    + "requested contact id, in request order.")
public record BatchMemberResponse(
    @Schema(description = "Per-item outcomes.", requiredMode = Schema.RequiredMode.REQUIRED)
    List<BatchMemberResult> results,

    @Schema(description = "Items processed successfully (CREATED, EXISTS, or NOT_FOUND for removals).",
        requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    int processed,

    @Schema(description = "Items that failed with status ERROR.", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    int failed
) {
}
