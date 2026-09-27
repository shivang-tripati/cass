package com.shivang.obd.ivr.dto;

import com.shivang.obd.voice.ivr.IvrNodeType;
import com.shivang.obd.voice.ivr.IvrTerminalAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One edge of an IVR menu (VB-6F).
 *
 * <p>The target is named by {@code targetNodeKey} rather than a server-issued
 * id, so a whole tree is authorable in one request. It must resolve to a node in
 * the <em>same</em> tree: cross-tree navigation is refused, both by validation
 * and by composite foreign keys in V53, because a tree that could reach into
 * another tree would make both ownership and snapshotting ambiguous.
 *
 * @param input        a single DTMF character: 0-9, * or #
 * @param targetNodeKey the node this digit leads to
 */
public record IvrTransitionRequest(
        @NotBlank
        @Pattern(regexp = "[0-9*#]", message = "input must be a single DTMF character: 0-9, * or #")
        @Schema(description = "A single DTMF character. The accepted alphabet is 0-9, * and #, "
                + "identical to the existing DTMF runtime; no other input semantics exist.",
                example = "1")
        String input,

        @NotBlank
        @Size(max = 64)
        @Schema(description = "nodeKey of the node this digit leads to. Must be a node of this "
                + "same tree; a target in another tree is rejected at validation and is also "
                + "unrepresentable in the database.",
                example = "SALES")
        String targetNodeKey) {
}
