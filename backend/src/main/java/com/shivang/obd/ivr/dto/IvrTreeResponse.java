package com.shivang.obd.ivr.dto;

import com.shivang.obd.voice.ivr.IvrNodeType;
import com.shivang.obd.voice.ivr.IvrTerminalAction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A tree as returned by the API, with its structure inline (VB-6F).
 *
 * <p>The menu is directly readable from this response: nodes carry their own
 * transitions, so an operator can see exactly what a caller experiences without
 * a second call. That is the inspection surface the brief asks for, and the
 * reason the response mirrors the authoring request rather than exposing
 * separate node resources.
 *
 * @param id          tree identity
 * @param status      lifecycle state; only ACTIVE trees may be selected for execution
 * @param rootNodeKey the entry node
 * @param nodes       the whole tree
 */
public record IvrTreeResponse(
        UUID id,
        String name,
        String description,
        IvrTreeStatusDto status,
        String rootNodeKey,
        UUID sourceCampaignId,
        List<IvrNodeResponse> nodes,
        Instant createdAt,
        Instant updatedAt) {

    /** REST-facing mirror of {@code IvrTreeStatus}, so the API contract is independent of internals. */
    public enum IvrTreeStatusDto {
        DRAFT, ACTIVE, ARCHIVED
    }

    /**
     * A node with its transitions.
     *
     * @param nodeKey stable key; also what transitions target
     */
    public record IvrNodeResponse(
            String nodeKey,
            IvrNodeType nodeType,
            UUID promptAudioAssetId,
            Integer inputWaitSeconds,
            UUID invalidPromptAudioAssetId,
            Integer invalidInputRetries,
            UUID noInputPromptAudioAssetId,
            Integer noInputRetries,
            IvrTerminalAction terminalAction,
            List<IvrTransitionResponse> transitions) {
    }

    /**
     * One edge.
     *
     * @param input        the DTMF character
     * @param targetNodeKey the node it leads to
     */
    public record IvrTransitionResponse(
            @Schema(description = "A single DTMF character: 0-9, * or #.", example = "1")
            String input,
            @Schema(description = "nodeKey of the node this digit leads to.", example = "SALES")
            String targetNodeKey) {
    }
}
