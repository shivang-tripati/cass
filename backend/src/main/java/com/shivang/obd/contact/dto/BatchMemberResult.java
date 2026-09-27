package com.shivang.obd.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Outcome of one item in a batch member add/remove operation.
 */
@Schema(description = "Per-item outcome of a batch member operation.")
public record BatchMemberResult(
    @Schema(description = "The requested contact id.", requiredMode = Schema.RequiredMode.REQUIRED)
    UUID contactId,

    @Schema(description = "What happened to this item.", requiredMode = Schema.RequiredMode.REQUIRED)
    MemberBatchStatus status,

    @Schema(description = "Coarse, non-leaking reason when status is ERROR; null otherwise. "
        + "Never exposes database or internal exception details.",
        nullable = true,
        example = "Concurrent modification; retry this item.")
    String errorDetail
) {

    public static BatchMemberResult of(UUID contactId, MemberBatchStatus status) {
        return new BatchMemberResult(contactId, status, null);
    }

    public static BatchMemberResult error(UUID contactId, String detail) {
        return new BatchMemberResult(contactId, MemberBatchStatus.ERROR, detail);
    }
}
