package com.shivang.obd.ivr.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Replace the editable contents of a tree (VB-6F).
 *
 * <p>Name and description are omitted: they are not execution-affecting, and
 * changing them must not require revalidating the flow. Everything the runtime
 * reads — nodes, transitions, prompts, timing, retries, terminal actions — is
 * replaced wholesale so the tree cannot be left half-updated.
 *
 * <p>An update to an {@code ACTIVE} tree is allowed and does not disturb
 * running executions: each execution captured its own immutable snapshot, so a
 * caller already in the old flow keeps the old flow. The change applies to
 * executions created afterwards. This is the VB-6A immutability rule applied to a
 * reusable resource, and it is why VB-6F needs no IVR versioning.
 */
public record UpdateIvrTreeRequest(
        @NotBlank
        @Size(max = 64)
        @Schema(description = "nodeKey of the entry node.", example = "ROOT")
        String rootNodeKey,

        @NotEmpty
        @Size(max = 200)
        @Valid
        @Schema(description = "The tree's complete node set, replacing the previous one.")
        List<IvrNodeRequest> nodes) {
}
