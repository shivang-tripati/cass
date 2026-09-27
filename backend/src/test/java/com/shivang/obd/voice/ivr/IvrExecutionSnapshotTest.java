package com.shivang.obd.voice.ivr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * VB-6F — the frozen execution snapshot and its codec.
 *
 * <p>The snapshot is the whole immutability guarantee, so what matters here is
 * that it is <em>self-contained</em> (traversal needs no live read) and that it
 * round-trips exactly (a snapshot that lost a transition on the way to the
 * database would fail a live call in a way nobody could diagnose).
 */
class IvrExecutionSnapshotTest {

    private static final UUID TREE = UUID.fromString("11111111-0000-4000-8000-000000000001");
    private static final UUID ASSET = UUID.fromString("22222222-0000-4000-8000-000000000002");
    private static final UUID BAD_ASSET = UUID.fromString("33333333-0000-4000-8000-000000000003");

    private static IvrNodeSnapshot menu(String key, int wait, int invalidRetries,
                                        int noInputRetries, Map<String, String> edges) {
        return new IvrNodeSnapshot(key, IvrNodeType.MENU, ASSET, wait, BAD_ASSET,
                invalidRetries, null, noInputRetries, null, edges);
    }

    /** ROOT -> SALES|SUPPORT, each to a terminal leaf. Two levels. */
    private static IvrExecutionSnapshot twoLevel() {
        Map<String, IvrNodeSnapshot> nodes = new LinkedHashMap<>();
        nodes.put("ROOT", menu("ROOT", 10, 2, 1, Map.of("1", "SALES", "2", "SUPPORT")));
        nodes.put("SALES", menu("SALES", 15, 0, 0, Map.of("1", "LEAF")));
        nodes.put("SUPPORT", menu("SUPPORT", 20, 3, 2, Map.of("2", "LEAF")));
        nodes.put("LEAF", new IvrNodeSnapshot("LEAF", IvrNodeType.TERMINAL, null, 5,
                null, 0, null, 0, IvrTerminalAction.CONNECT_BY_AGENT, Map.of()));
        return new IvrExecutionSnapshot(TREE, "Support Menu", "ROOT", nodes);
    }

    @Nested
    class Traversal {

        @Test
        @DisplayName("SNAP-1: a declared digit resolves to its target node key")
        void validDigitResolves() {
            assertThat(twoLevel().resolve('1', "ROOT")).contains("SALES");
            assertThat(twoLevel().resolve('2', "ROOT")).contains("SUPPORT");
        }

        @Test
        @DisplayName("SNAP-2: an undeclared digit resolves to nothing, which is an invalid input")
        void undeclaredDigitIsEmpty() {
            assertThat(twoLevel().resolve('9', "ROOT")).isEmpty();
        }

        @Test
        @DisplayName("SNAP-3: a caller can walk several levels deep")
        void multiLevelWalk() {
            IvrExecutionSnapshot snapshot = twoLevel();
            // ROOT -> SALES -> LEAF, exactly the brief's Example 2.
            String at = "ROOT";
            at = snapshot.resolve('1', at).orElseThrow();
            assertThat(at).isEqualTo("SALES");
            at = snapshot.resolve('1', at).orElseThrow();
            assertThat(at).isEqualTo("LEAF");
            assertThat(snapshot.node(at).orElseThrow().terminal()).isTrue();
        }

        @Test
        @DisplayName("SNAP-4: the root is always resolvable")
        void rootAlwaysPresent() {
            assertThat(twoLevel().root().nodeKey()).isEqualTo("ROOT");
            assertThat(twoLevel().root().nodeType()).isEqualTo(IvrNodeType.MENU);
        }

        @Test
        @DisplayName("SNAP-5: a digit pressed at an unknown node resolves to nothing")
        void unknownNodeIsEmpty() {
            assertThat(twoLevel().resolve('1', "NOWHERE")).isEmpty();
            assertThat(twoLevel().resolve('1', null)).isEmpty();
        }

        @Test
        @DisplayName("SNAP-6: a terminal node's action is readable from the snapshot")
        void terminalActionReadable() {
            assertThat(twoLevel().node("LEAF").orElseThrow().terminalAction())
                    .isEqualTo(IvrTerminalAction.CONNECT_BY_AGENT);
        }

        @Test
        @DisplayName("SNAP-7: the whole DTMF alphabet resolves through a menu")
        void fullAlphabetResolves() {
            Map<String, String> edges = new LinkedHashMap<>();
            for (char digit : "0123456789*#".toCharArray()) {
                edges.put(String.valueOf(digit), "LEAF");
            }
            Map<String, IvrNodeSnapshot> nodes = new LinkedHashMap<>();
            nodes.put("ROOT", menu("ROOT", 10, 0, 0, edges));
            nodes.put("LEAF", new IvrNodeSnapshot("LEAF", IvrNodeType.TERMINAL, null, 5,
                    null, 0, null, 0, IvrTerminalAction.TERMINATE, Map.of()));
            IvrExecutionSnapshot snapshot = new IvrExecutionSnapshot(TREE, "Full", "ROOT", nodes);

            for (char digit : "0123456789*#".toCharArray()) {
                assertThat(snapshot.resolve(digit, "ROOT"))
                        .as("digit %s must resolve", digit)
                        .contains("LEAF");
            }
        }
    }

    @Nested
    class Immutability {

        @Test
        @DisplayName("SNAP-8: the transition map cannot be mutated after construction")
        void transitionsAreImmutable() {
            IvrNodeSnapshot node = twoLevel().node("ROOT").orElseThrow();
            assertThatThrownBy(() -> node.transitions().put("9", "SUPPORT"))
                    .as("a runtime that could edit its own snapshot would break the guarantee")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("SNAP-9: the node map cannot be mutated after construction")
        void nodesAreImmutable() {
            IvrExecutionSnapshot snapshot = twoLevel();
            assertThatThrownBy(() -> snapshot.nodes().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Construction {

        @Test
        @DisplayName("SNAP-10: a snapshot whose root is not among its nodes is refused")
        void missingRootRefused() {
            Map<String, IvrNodeSnapshot> nodes = new LinkedHashMap<>();
            nodes.put("A", menu("A", 10, 0, 0, Map.of()));

            assertThatThrownBy(() -> new IvrExecutionSnapshot(TREE, "x", "MISSING", nodes))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("MISSING");
        }

        @Test
        @DisplayName("SNAP-11: an empty snapshot is refused")
        void emptySnapshotRefused() {
            assertThatThrownBy(() -> new IvrExecutionSnapshot(TREE, "x", "A", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("SNAP-12: a node with a non-positive wait is refused")
        void nonPositiveWaitRefused() {
            assertThatThrownBy(() -> new IvrNodeSnapshot(
                    "A", IvrNodeType.MENU, null, 0, null, 0, null, 0, null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("inputWaitSeconds");
        }

        @Test
        @DisplayName("SNAP-13: a node with a negative retry count is refused")
        void negativeRetriesRefused() {
            assertThatThrownBy(() -> new IvrNodeSnapshot(
                    "A", IvrNodeType.MENU, null, 10, null, -1, null, 0, null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }
    }

    @Nested
    class Codec {

        @Test
        @DisplayName("SNAP-14: a snapshot round-trips through JSON without losing anything")
        void roundTripIsLossless() {
            IvrExecutionSnapshot original = twoLevel();
            IvrExecutionSnapshot restored = IvrSnapshotCodec.fromJson(
                    IvrSnapshotCodec.toJson(original));

            assertThat(restored.treeId()).isEqualTo(original.treeId());
            assertThat(restored.treeName()).isEqualTo(original.treeName());
            assertThat(restored.rootNodeKey()).isEqualTo(original.rootNodeKey());
            assertThat(restored.nodes()).hasSameSizeAs(original.nodes());

            // Every prompt, budget, timing, terminal action and edge must survive,
            // because a lost transition fails a live call.
            for (IvrNodeSnapshot node : original.nodes().values()) {
                IvrNodeSnapshot back = restored.node(node.nodeKey()).orElseThrow();
                assertThat(back.nodeType()).isEqualTo(node.nodeType());
                assertThat(back.promptAudioAssetId()).isEqualTo(node.promptAudioAssetId());
                assertThat(back.inputWaitSeconds()).isEqualTo(node.inputWaitSeconds());
                assertThat(back.invalidPromptAudioAssetId())
                        .isEqualTo(node.invalidPromptAudioAssetId());
                assertThat(back.invalidInputRetries()).isEqualTo(node.invalidInputRetries());
                assertThat(back.noInputPromptAudioAssetId())
                        .isEqualTo(node.noInputPromptAudioAssetId());
                assertThat(back.noInputRetries()).isEqualTo(node.noInputRetries());
                assertThat(back.terminalAction()).isEqualTo(node.terminalAction());
                assertThat(back.transitions()).isEqualTo(node.transitions());
            }
        }

        @Test
        @DisplayName("SNAP-15: a restored snapshot traverses identically")
        void restoredSnapshotTraversesIdentically() {
            IvrExecutionSnapshot original = twoLevel();
            IvrExecutionSnapshot restored = IvrSnapshotCodec.fromJson(
                    IvrSnapshotCodec.toJson(original));

            for (char digit : "0123456789*#".toCharArray()) {
                for (String node : List.of("ROOT", "SALES", "SUPPORT")) {
                    assertThat(restored.resolve(digit, node))
                            .as("%s on %s", digit, node)
                            .isEqualTo(original.resolve(digit, node));
                }
            }
        }

        @Test
        @DisplayName("SNAP-16: the written form is deterministic")
        void writingIsDeterministic() {
            IvrExecutionSnapshot snapshot = twoLevel();
            assertThat(IvrSnapshotCodec.toJson(snapshot).toString())
                    .as("the same tree must always produce byte-identical JSON")
                    .isEqualTo(IvrSnapshotCodec.toJson(snapshot).toString());
        }

        @Test
        @DisplayName("SNAP-17: a node key containing a dot round-trips")
        void dottedNodeKeysRoundTrip() {
            // Keys are written as an array of objects rather than a nested map
            // precisely so a key that is not a safe map key still round-trips.
            Map<String, IvrNodeSnapshot> nodes = new LinkedHashMap<>();
            nodes.put("a.b", menu("a.b", 10, 0, 0, Map.of("1", "leaf.1")));
            nodes.put("leaf.1", new IvrNodeSnapshot("leaf.1", IvrNodeType.TERMINAL, null, 5,
                    null, 0, null, 0, IvrTerminalAction.TERMINATE, Map.of()));
            IvrExecutionSnapshot snapshot = new IvrExecutionSnapshot(TREE, "Dotted", "a.b", nodes);

            IvrExecutionSnapshot restored = IvrSnapshotCodec.fromJson(
                    IvrSnapshotCodec.toJson(snapshot));
            assertThat(restored.rootNodeKey()).isEqualTo("a.b");
            assertThat(restored.resolve('1', "a.b")).contains("leaf.1");
        }

        @Test
        @DisplayName("SNAP-18: a type config with no ivr entry reads as absent, not malformed")
        void absentEntryIsEmpty() {
            assertThat(IvrSnapshotCodec.read(null)).isEmpty();
            assertThat(IvrSnapshotCodec.read(
                    tools.jackson.databind.node.JsonNodeFactory.instance.objectNode())).isEmpty();
        }

        @Test
        @DisplayName("SNAP-19: a malformed snapshot is refused rather than half-read")
        void malformedSnapshotRefused() {
            ObjectNode broken = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            broken.put("rootNodeKey", "ROOT"); // nodes array missing entirely

            assertThatThrownBy(() -> IvrSnapshotCodec.fromJson(broken))
                    .isInstanceOf(IvrSnapshotCodec.IvrSnapshotInvalidException.class)
                    .hasMessageContaining("nodes");
        }

        @Test
        @DisplayName("SNAP-20: a snapshot naming an unknown enum value is refused")
        void unknownEnumRefused() {
            ObjectNode broken = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            var node = broken.putArray("nodes").addObject();
            node.put("nodeKey", "ROOT");
            node.put("nodeType", "WORKFLOW_STEP");
            broken.put("rootNodeKey", "ROOT");

            assertThatThrownBy(() -> IvrSnapshotCodec.fromJson(broken))
                    .isInstanceOf(IvrSnapshotCodec.IvrSnapshotInvalidException.class);
        }
    }
}
