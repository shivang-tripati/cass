package com.shivang.obd.voice.ivr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Serialises an {@link IvrExecutionSnapshot} to and from JSON (VB-6F).
 *
 * <h2>Where the snapshot is stored</h2>
 *
 * <p>Inside the <em>existing</em> {@code campaign_execution_configurations.type_config}
 * JSONB column, under an {@code "ivr"} key. That column is already
 * {@code updatable = false} and written once at execution creation, so reusing
 * it means VB-6F needs no snapshot table, no IVR versioning, no
 * {@code MAX(version)+1}, and no history — the immutability guarantee VB-6A
 * established for every other execution-affecting field applies to the IVR for
 * free.
 *
 * <h2>Why a codec and not plain Jackson binding</h2>
 *
 * <p>The persisted shape is explicit and hand-written for two reasons: the
 * {@code nodeKey -> transitions} map is written as an array of objects rather
 * than a nested map, so a tree with a node key containing a dot or a slash still
 * round-trips; and enums are written by name, so a later enum addition cannot
 * silently reinterpret a stored snapshot. Reading is strict: a malformed or
 * incomplete snapshot throws {@link IvrSnapshotInvalidException} rather than
 * yielding a half-built value, because a partially-understood IVR would fail
 * live calls in a way nobody could diagnose from the configuration.
 *
 * <p>Round-trip fidelity is asserted by test.
 */
public final class IvrSnapshotCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IvrSnapshotCodec() {
    }

    /** A stored IVR snapshot that cannot be read back. */
    public static class IvrSnapshotInvalidException extends RuntimeException {
        public IvrSnapshotInvalidException(String message) {
            super(message);
        }

        public IvrSnapshotInvalidException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The key the snapshot occupies inside the campaign type_config object. */
    public static final String KEY = "ivr";

    /**
     * Reads the {@code "ivr"} entry of a campaign type_config.
     *
     * @return the snapshot, or empty when the config carries no IVR — which is
     *         the normal case for a campaign still on the single-level DTMF path
     */
    public static java.util.Optional<IvrExecutionSnapshot> read(JsonNode typeConfig) {
        if (typeConfig == null || !typeConfig.isObject()) {
            return java.util.Optional.empty();
        }
        JsonNode ivr = typeConfig.get(KEY);
        if (ivr == null || ivr.isNull()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(fromJson(ivr));
    }

    /** Rebuilds a snapshot from its stored form. */
    public static IvrExecutionSnapshot fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IvrSnapshotInvalidException("IVR snapshot is missing or not an object");
        }
        try {
            String treeIdText = text(node, "treeId");
            String rootKey = text(node, "rootNodeKey");
            JsonNode nodesNode = node.get("nodes");
            if (nodesNode == null || !nodesNode.isArray()) {
                throw new IvrSnapshotInvalidException("IVR snapshot has no nodes array");
            }

            Map<String, IvrNodeSnapshot> nodes = new LinkedHashMap<>();
            for (JsonNode each : nodesNode) {
                IvrNodeSnapshot snapshot = readNode(each);
                nodes.put(snapshot.nodeKey(), snapshot);
            }

            return new IvrExecutionSnapshot(
                    treeIdText == null ? null : UUID.fromString(treeIdText),
                    text(node, "treeName"),
                    rootKey,
                    nodes);
        } catch (IvrSnapshotInvalidException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IvrSnapshotInvalidException("IVR snapshot could not be read: "
                    + e.getMessage(), e);
        }
    }

    private static IvrNodeSnapshot readNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IvrSnapshotInvalidException("An IVR snapshot node is not an object");
        }
        String nodeKey = text(node, "nodeKey");
        JsonNode transitionsNode = node.get("transitions");
        Map<String, String> transitions = new LinkedHashMap<>();
        if (transitionsNode != null && transitionsNode.isArray()) {
            for (JsonNode each : transitionsNode) {
                transitions.put(text(each, "input"), text(each, "target"));
            }
        }
        try {
            return new IvrNodeSnapshot(
                    nodeKey,
                    IvrNodeType.valueOf(text(node, "nodeType")),
                    uuid(node.get("promptAudioAssetId")),
                    node.path("inputWaitSeconds").asInt(10),
                    uuid(node.get("invalidPromptAudioAssetId")),
                    node.path("invalidInputRetries").asInt(0),
                    uuid(node.get("noInputPromptAudioAssetId")),
                    node.path("noInputRetries").asInt(0),
                    node.hasNonNull("terminalAction")
                            ? IvrTerminalAction.valueOf(text(node, "terminalAction"))
                            : null,
                    transitions);
        } catch (IllegalArgumentException e) {
            throw new IvrSnapshotInvalidException(
                    "IVR snapshot node '" + nodeKey + "' is invalid: " + e.getMessage(), e);
        }
    }

    /** Writes a snapshot as the {@code "ivr"} entry of a type_config object. */
    public static void writeInto(ObjectNode typeConfig, IvrExecutionSnapshot snapshot) {
        typeConfig.set(KEY, toJson(snapshot));
    }

    /** Writes a snapshot as a standalone node. */
    public static ObjectNode toJson(IvrExecutionSnapshot snapshot) {
        ObjectNode root = MAPPER.createObjectNode();
        if (snapshot.treeId() != null) {
            root.put("treeId", snapshot.treeId().toString());
        }
        if (snapshot.treeName() != null) {
            root.put("treeName", snapshot.treeName());
        }
        root.put("rootNodeKey", snapshot.rootNodeKey());
        root.put("schemaVersion", 1);

        ArrayNode nodes = MAPPER.createArrayNode();
        for (IvrNodeSnapshot node : snapshot.nodes().values()) {
            ObjectNode json = MAPPER.createObjectNode();
            json.put("nodeKey", node.nodeKey());
            json.put("nodeType", node.nodeType().name());
            json.put("inputWaitSeconds", node.inputWaitSeconds());
            json.put("invalidInputRetries", node.invalidInputRetries());
            json.put("noInputRetries", node.noInputRetries());
            if (node.promptAudioAssetId() != null) {
                json.put("promptAudioAssetId", node.promptAudioAssetId().toString());
            }
            if (node.invalidPromptAudioAssetId() != null) {
                json.put("invalidPromptAudioAssetId",
                        node.invalidPromptAudioAssetId().toString());
            }
            if (node.noInputPromptAudioAssetId() != null) {
                json.put("noInputPromptAudioAssetId",
                        node.noInputPromptAudioAssetId().toString());
            }
            if (node.terminalAction() != null) {
                json.put("terminalAction", node.terminalAction().name());
            }
            ArrayNode transitions = MAPPER.createArrayNode();
            node.transitions().forEach((input, target) -> {
                ObjectNode edge = MAPPER.createObjectNode();
                edge.put("input", input);
                edge.put("target", target);
                transitions.add(edge);
            });
            json.set("transitions", transitions);
            nodes.add(json);
        }
        root.set("nodes", nodes);
        return root;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return UUID.fromString(node.asText());
    }
}
