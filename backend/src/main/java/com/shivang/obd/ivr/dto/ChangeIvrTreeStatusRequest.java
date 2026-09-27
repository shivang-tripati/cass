package com.shivang.obd.ivr.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Move a tree through its lifecycle (VB-6F).
 *
 * <p>Only two transitions are legal, and both are checked:
 * {@code DRAFT -> ACTIVE} and {@code ACTIVE -> ARCHIVED}. Activation runs the
 * full structural validator plus prompt-resource governance, so a tree that
 * cannot be executed never becomes selectable. Archiving is not a delete: a
 * captured execution snapshot is self-contained, so historical calls keep
 * working and remain auditable.
 */
public record ChangeIvrTreeStatusRequest(

        @NotNull
        @Schema(description = "Target lifecycle state. Only DRAFT->ACTIVE and ACTIVE->ARCHIVED are "
                + "permitted. A tree is validated before it may become ACTIVE, so a broken flow "
                + "cannot be selected for execution.",
                example = "ACTIVE")
        IvrTreeResponse.IvrTreeStatusDto status) {
}
