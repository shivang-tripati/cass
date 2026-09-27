package com.shivang.obd.voice.ivr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One node, frozen, as the execution sees it (VB-6F).
 *
 * @param nodeKey                  stable key within the tree
 * @param nodeType                 MENU or TERMINAL
 * @param promptAudioAssetId       the node's prompt, or null for a silent menu
 * @param inputWaitSeconds         frozen wait for one digit
 * @param invalidPromptAudioAssetId played on an unrecognised digit, or null for silence
 * @param invalidInputRetries      ADDITIONAL attempts after the first
 * @param noInputPromptAudioAssetId played when the wait elapsed with no digit
 * @param noInputRetries           ADDITIONAL attempts after the first
 * @param terminalAction           set on TERMINAL nodes only
 * @param transitions              DTMF digit to target <em>node key</em>
 */
public record IvrNodeSnapshot(
        String nodeKey,
        IvrNodeType nodeType,
        UUID promptAudioAssetId,
        int inputWaitSeconds,
        UUID invalidPromptAudioAssetId,
        int invalidInputRetries,
        UUID noInputPromptAudioAssetId,
        int noInputRetries,
        IvrTerminalAction terminalAction,
        Map<String, String> transitions) {

    public IvrNodeSnapshot {
        if (nodeKey == null || nodeKey.isBlank()) {
            throw new IllegalArgumentException("nodeKey is required");
        }
        if (nodeType == null) {
            throw new IllegalArgumentException("nodeType is required for node " + nodeKey);
        }
        if (inputWaitSeconds <= 0) {
            throw new IllegalArgumentException(
                    "inputWaitSeconds must be positive for node " + nodeKey);
        }
        if (invalidInputRetries < 0 || noInputRetries < 0) {
            throw new IllegalArgumentException(
                    "retry counts must not be negative for node " + nodeKey);
        }
        transitions = transitions == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(transitions));
    }

    public boolean terminal() {
        return nodeType == IvrNodeType.TERMINAL;
    }

    /**
     * The node key this digit leads to, or empty when the node has no
     * transition for it — which is precisely the definition of an invalid input.
     */
    public Optional<String> targetFor(char digit) {
        return Optional.ofNullable(transitions.get(String.valueOf(digit)));
    }
}
