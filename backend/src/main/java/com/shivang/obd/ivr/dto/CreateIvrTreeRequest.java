package com.shivang.obd.ivr.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Author or replace a reusable IVR tree (VB-6F).
 *
 * <p>The whole tree is submitted at once. A tree is only meaningful as a
 * complete structure — exactly one root, no cycles, every node reachable — so it
 * is validated as a unit and written in one transaction. There are deliberately
 * no per-node endpoints: they would let a client persist a tree that is invalid
 * between two requests, which is the state that must never reach a live call.
 *
 * @param name         display name, unique per tenant in practice
 * @param description  optional
 * @param rootNodeKey  which authored node is the entry point
 * @param nodes        every node, each with its own transitions
 */
public record CreateIvrTreeRequest(
        @NotBlank
        @Size(max = 120)
        @Schema(description = "Display name for the tree.", example = "Customer Support Menu")
        String name,

        @Size(max = 512)
        @Schema(description = "Optional description of what the flow does.",
                example = "Routes callers to sales or support, then to a team")
        String description,

        @NotBlank
        @Size(max = 64)
        @Schema(description = "nodeKey of the entry node. Exactly one root is required; the tree "
                + "cannot be activated without it.", example = "ROOT")
        String rootNodeKey,

        @NotEmpty
        @Size(max = 200)
        @Schema(description = "Every node of the tree, MENU and TERMINAL alike. A caller may "
                + "reach any of them, so the tree is validated as a whole rather than "
                + "level by level.")
        @Valid
        List<IvrNodeRequest> nodes) {
}
