package com.shivang.obd.ivr.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.shivang.obd.voice.ivr.IvrNodeType;
import com.shivang.obd.voice.ivr.IvrTerminalAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * One node as authored on the REST surface (VB-6F).
 *
 * <p>Nodes and their transitions are submitted <em>inline with the tree</em>
 * rather than through per-node CRUD endpoints, because a tree is only ever
 * meaningful as a whole: it must have exactly one root, no cycles and no
 * unreachable node. Per-node endpoints would let a client persist a tree that is
 * invalid between two calls, which is precisely the state the brief forbids
 * reaching a live call.
 *
 * <p>Transitions name their target by {@code targetNodeKey}, so a whole tree can
 * be authored in one request without the client needing server-generated UUIDs.
 * That is also what makes the reference inspectable: the API shows the menu
 * exactly as a caller experiences it.
 *
 * @param nodeKey                  stable identifier, unique within the tree
 * @param nodeType                 MENU or TERMINAL
 * @param promptAudioAssetId       prompt for this node; must be an APPROVED asset of the caller's tenant
 * @param inputWaitSeconds         how long to wait for one digit (1-120)
 * @param invalidPromptAudioAssetId played on an unrecognised digit
 * @param invalidInputRetries      ADDITIONAL attempts after the first, 0-10
 * @param noInputPromptAudioAssetId played when the wait elapses with no digit
 * @param noInputRetries           ADDITIONAL attempts after the first, 0-10
 * @param terminalAction           TERMINATE or CONNECT_BY_AGENT; TERMINAL nodes only
 * @param transitions              the digits this node accepts
 */
public record IvrNodeRequest(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9_.-]+",
                message = "nodeKey may contain only letters, digits, underscore, dot and dash")
        @Schema(description = "Stable identifier unique within the tree. It is the tree's "
                + "structure in the API response and the key the execution snapshot freezes, so it "
                + "should be meaningful (for example ROOT, SALES, BILLING).",
                example = "SALES")
        String nodeKey,

        @NotNull
        @Schema(description = "MENU asks one question and follows the transition for the digit "
                + "pressed. TERMINAL ends the flow and must declare a terminalAction.",
                example = "MENU")
        com.shivang.obd.voice.ivr.IvrNodeType nodeType,

        @Schema(description = "Approved audio asset used as this node's prompt. Ownership and "
                + "approval are checked against the caller's tenant by the same resource authority "
                + "campaigns use; a foreign or unapproved asset is rejected.")
        UUID promptAudioAssetId,

        @Min(1)
        @Max(120)
        @Schema(description = "Seconds to wait for one DTMF digit, 1-120. Null uses the platform "
                + "default of 10. The range matches the existing DTMF collection window.",
                example = "10")
        Integer inputWaitSeconds,

        @Schema(description = "Approved audio asset played when the caller presses a digit with no "
                + "transition. Optional; without it the caller is simply re-asked silently.")
        UUID invalidPromptAudioAssetId,

        @Min(0)
        @Max(10)
        @Schema(description = "How many ADDITIONAL times to re-ask after an unrecognised digit. "
                + "0 means one attempt in total, then the node's terminal behaviour. This is an "
                + "in-call interaction retry: it never creates a new call attempt and never invokes "
                + "the campaign retry policy.",
                example = "2")
        Integer invalidInputRetries,

        @Schema(description = "Approved audio asset played when no digit arrives within "
                + "inputWaitSeconds. Optional.")
        UUID noInputPromptAudioAssetId,

        @Min(0)
        @Max(10)
        @Schema(description = "How many ADDITIONAL times to re-ask after no input at all. Same "
                + "semantics as invalidInputRetries: 0 means one attempt in total. This is a "
                + "distinct outcome from an invalid digit and keeps its own budget.",
                example = "2")
        Integer noInputRetries,

        @Schema(description = "What happens when a caller reaches this TERMINAL node. "
                + "CONNECT_BY_AGENT reuses the existing agent-connect boundary; an agent-less "
                + "deployment records a permanent configuration failure rather than failing silently.",
                example = "TERMINATE")
        com.shivang.obd.voice.ivr.IvrTerminalAction terminalAction,

        @Valid
        @Size(max = 12)
        @Schema(description = "The digits this node accepts. Each digit may appear at most once, "
                + "and its target must be a nodeKey in this same tree. Cross-tree targets are "
                + "rejected.")
        List<IvrTransitionRequest> transitions) {
}
