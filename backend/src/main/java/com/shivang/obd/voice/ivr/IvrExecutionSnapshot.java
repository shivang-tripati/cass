package com.shivang.obd.voice.ivr;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A reusable IVR tree, frozen for the lifetime of one execution (VB-6F).
 *
 * <h2>Why this shape</h2>
 *
 * <p>Three properties, and the shape is chosen to get all three at once:
 *
 * <ul>
 *   <li><b>Flat and keyed.</b> Nodes live in a map keyed by {@code nodeKey}, and
 *       transitions are frozen as digit to target <em>key</em> rather than
 *       target UUID. Traversal is therefore a map lookup: no database read, no
 *       UUID resolution, and no ordering ambiguity. Editing a live IVR cannot
 *       affect a running call because the runtime never looks at the live rows
 *       again — this value is the only thing it reads.</li>
 *   <li><b>Self-contained.</b> Everything needed to execute the flow is here,
 *       including prompts and terminal actions, so an archived or soft-deleted
 *       tree leaves its historical executions fully runnable and auditable.</li>
 *   <li><b>Immutable.</b> Every collection is copied at construction and the
 *       record's own fields are final, so nothing can mutate a running call's
 *       configuration.</li>
 * </ul>
 *
 * <h2>What is deliberately NOT frozen</h2>
 *
 * <p>Prompt assets are frozen as <em>identities</em>, and ownership, approval and
 * storage availability are still checked at playback time through the existing
 * canonical resource authority. That is the VB-6A §9 split — the snapshot pins
 * <em>what was requested</em>, resource governance stays dynamic — and inventing
 * a different split here would be a new snapshot philosophy.
 *
 * @param treeId       identity of the source tree, for audit
 * @param treeName     name at capture time, for logs
 * @param rootNodeKey  the entry node
 * @param nodes        every node, keyed by {@code nodeKey}
 */
public record IvrExecutionSnapshot(
        UUID treeId,
        String treeName,
        String rootNodeKey,
        Map<String, IvrNodeSnapshot> nodes) {

    public IvrExecutionSnapshot {
        if (rootNodeKey == null || rootNodeKey.isBlank()) {
            throw new IllegalArgumentException("rootNodeKey is required");
        }
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("An IVR snapshot needs at least one node");
        }
        if (!nodes.containsKey(rootNodeKey)) {
            throw new IllegalArgumentException(
                    "Snapshot root '" + rootNodeKey + "' is not among its nodes");
        }
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }

    /** The root node. Always present — the constructor enforces it. */
    public IvrNodeSnapshot root() {
        return nodes.get(rootNodeKey);
    }

    public Optional<IvrNodeSnapshot> node(String nodeKey) {
        return nodeKey == null ? Optional.empty() : Optional.ofNullable(nodes.get(nodeKey));
    }

    /**
     * Resolves a digit pressed at a node to the node key it leads to.
     * <p>
     * The single place traversal decides an input's meaning. A missing node or a
     * missing transition both yield empty, which the runtime treats as an
     * invalid input — the honest outcome for a snapshot that disagrees with
     * itself, and one the validator is supposed to have prevented.
     */
    public Optional<String> resolve(char digit, String fromNodeKey) {
        return node(fromNodeKey)
                .flatMap(n -> n.targetFor(digit));
    }
}
