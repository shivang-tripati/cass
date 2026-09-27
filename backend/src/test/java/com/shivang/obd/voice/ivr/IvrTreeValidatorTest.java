package com.shivang.obd.voice.ivr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VB-6F — the single authoritative structural validator.
 *
 * <p>Pure, so every rule is directly testable. These are the checks the brief
 * requires before anything can be dialled: a broken tree must be discovered while
 * it is being authored, never by a caller pressing a key that leads nowhere.
 */
class IvrTreeValidatorTest {

    private static final UUID TREE = UUID.fromString("11111111-0000-4000-8000-000000000001");
    private static final UUID OTHER_TREE = UUID.fromString("22222222-0000-4000-8000-000000000002");

    /** Keyed by nodeKey for lookups; the list is authoritative for validation. */
    private final Map<String, IvrNode> nodes = new LinkedHashMap<>();
    private final List<IvrNode> nodeList = new ArrayList<>();
    private final List<IvrTransition> transitions = new ArrayList<>();

    private IvrNode node(String key, IvrNodeType type) {
        IvrNode node = new IvrNode();
        node.setId(UUID.randomUUID());
        node.setTreeId(TREE);
        node.setNodeKey(key);
        node.setNodeType(type);
        node.setInputWaitSeconds(10);
        node.setInvalidInputRetries(0);
        node.setNoInputRetries(0);
        if (type == IvrNodeType.TERMINAL) {
            node.setTerminalAction(IvrTerminalAction.TERMINATE);
        }
        nodes.put(key, node);
        nodeList.add(node);
        return node;
    }

    private void edge(String fromKey, String input, String toKey) {
        edge(fromKey, input, toKey, TREE);
    }

    private void edge(String fromKey, String input, String toKey, UUID targetTree) {
        IvrNode target = nodes.get(toKey);
        IvrTransition transition = new IvrTransition();
        transition.setId(UUID.randomUUID());
        transition.setTreeId(TREE);
        transition.setNodeId(nodes.get(fromKey).getId());
        transition.setDtmfInput(input);
        transition.setTargetNodeId(
                target != null ? target.getId() : UUID.randomUUID());
        transitions.add(transition);
    }

    private List<IvrTreeValidator.Violation> validate(String root) {
        return IvrTreeValidator.validate(root, new ArrayList<>(nodeList), transitions);
    }

    private List<String> codes(String root) {
        return validate(root).stream().map(IvrTreeValidator.Violation::code).toList();
    }

    /** A valid two-level tree: ROOT -> SALES|SUPPORT, each to a terminal leaf. */
    private void validTwoLevel() {
        node("ROOT", IvrNodeType.MENU);
        node("SALES", IvrNodeType.MENU);
        node("SUPPORT", IvrNodeType.MENU);
        node("LEAF", IvrNodeType.TERMINAL);
        edge("ROOT", "1", "SALES");
        edge("ROOT", "2", "SUPPORT");
        edge("SALES", "1", "LEAF");
        edge("SUPPORT", "9", "LEAF");
    }

    /** A transition from a node to itself, for the self-loop rule. */
    private IvrTransition selfTransition(String key, String input) {
        IvrNode node = nodes.get(key);
        IvrTransition transition = new IvrTransition();
        transition.setId(UUID.randomUUID());
        transition.setTreeId(TREE);
        transition.setNodeId(node.getId());
        transition.setDtmfInput(input);
        transition.setTargetNodeId(node.getId());
        transitions.add(transition);
        return transition;
    }
    @Nested
    class ValidTrees {

        @Test
        @DisplayName("VAL-1: a two-level tree with terminals is valid")
        void twoLevelTreeIsValid() {
            validTwoLevel();
            assertThat(validate("ROOT")).isEmpty();
        }

        @Test
        @DisplayName("VAL-2: a four-level chain is valid - depth is not limited to two")
        void deepChainIsValid() {
            // The brief explicitly forbids a one-level-only model.
            node("A", IvrNodeType.MENU);
            node("B", IvrNodeType.MENU);
            node("C", IvrNodeType.MENU);
            node("D", IvrNodeType.MENU);
            node("E", IvrNodeType.TERMINAL);
            edge("A", "1", "B");
            edge("B", "1", "C");
            edge("C", "1", "D");
            edge("D", "1", "E");
            assertThat(validate("A")).isEmpty();
        }

        @Test
        @DisplayName("VAL-3: a back-edge to an ancestor is a cycle and is rejected")
        void backwardTransitionIsAcycle() {
            // The brief's guidance is explicit: IVR trees are finite, not graphs,
            // and "go back to a previous menu" must not be modelled as an
            // unrestricted cycle. A back-edge IS a cycle by definition, so it is
            // refused. The audited alternative is a bounded, explicit structure
            // rather than a graph edge, which VB-6F does not introduce.
            node("ROOT", IvrNodeType.MENU);
            node("SUB", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "SUB");
            edge("SUB", "1", "LEAF");
            edge("SUB", "9", "ROOT");
            assertThat(codes("ROOT")).contains(IvrTreeValidator.CYCLE);
        }
    }

    @Nested
    class RootRules {

        @Test
        @DisplayName("VAL-4: a missing root is reported")
        void missingRootReported() {
            validTwoLevel();
            assertThat(codes(null)).contains(IvrTreeValidator.NO_ROOT);
            assertThat(codes("  ")).contains(IvrTreeValidator.NO_ROOT);
        }

        @Test
        @DisplayName("VAL-5: a root naming a node that does not exist is reported")
        void unknownRootReported() {
            validTwoLevel();
            assertThat(codes("NOWHERE")).contains(IvrTreeValidator.NO_ROOT);
        }

        @Test
        @DisplayName("VAL-6: two nodes sharing the root key is reported as multiple roots")
        void multipleRootsReported() {
            validTwoLevel();
            node("ROOT", IvrNodeType.TERMINAL); // a second node with the same key
            assertThat(codes("ROOT")).contains(IvrTreeValidator.DUPLICATE_NODE_KEY);
        }
    }

    @Nested
    class TransitionRules {

        @Test
        @DisplayName("VAL-7: the same digit twice on one node is reported")
        void duplicateInputReported() {
            node("ROOT", IvrNodeType.MENU);
            node("A", IvrNodeType.MENU);
            node("B", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "A");
            edge("ROOT", "1", "B");
            edge("A", "1", "LEAF");
            edge("B", "1", "LEAF");
            assertThat(codes("ROOT")).contains(IvrTreeValidator.DUPLICATE_TRANSITION);
        }

        @ParameterizedTest(name = "VAL-8: {0} is a valid DTMF input")
        @ValueSource(strings = {"0", "1", "5", "9", "*", "#"})
        @DisplayName("VAL-8: the full DTMF alphabet is accepted")
        void dtmfAlphabetAccepted(String input) {
            assertThat(IvrTreeValidator.isDtmfDigit(input.charAt(0))).isTrue();
        }

        @ParameterizedTest(name = "VAL-9: {0} is rejected")
        @ValueSource(strings = {"A", "a", "-", "+", " ", "10"})
        @DisplayName("VAL-9: anything outside 0-9/*/# is rejected")
        void nonDtmfRejected(String input) {
            // A multi-character string is not a single DTMF key either.
            assertThat(input.length() == 1
                    && IvrTreeValidator.isDtmfDigit(input.charAt(0))).isFalse();
        }

        @Test
        @DisplayName("VAL-10: an invalid digit on a transition is reported")
        void invalidDigitReported() {
            node("ROOT", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "LEAF");
            edge("ROOT", "A", "LEAF");
            assertThat(codes("ROOT")).contains(IvrTreeValidator.INVALID_DTMF);
        }

        @Test
        @DisplayName("VAL-11: a transition to a node that does not exist is reported")
        void danglingTargetReported() {
            node("ROOT", IvrNodeType.MENU);
            edge("ROOT", "1", "MISSING");
            assertThat(codes("ROOT")).contains(IvrTreeValidator.UNKNOWN_TARGET);
        }

        @Test
        @DisplayName("VAL-12: a transition into another tree is reported")
        void crossTreeTargetReported() {
            node("ROOT", IvrNodeType.MENU);
            IvrNode foreign = node("LEAF", IvrNodeType.TERMINAL);
            foreign.setTreeId(OTHER_TREE);
            edge("ROOT", "1", "LEAF");
            assertThat(codes("ROOT")).contains(IvrTreeValidator.CROSS_TREE_TARGET);
        }

        @Test
        @DisplayName("VAL-13: a node pointing at itself is reported")
        void selfLoopReported() {
            node("ROOT", IvrNodeType.MENU);
            node("LOOP", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "LOOP");
            edge("LOOP", "2", "LEAF");
            transitions.add(selfTransition("LOOP", "1"));
            assertThat(codes("ROOT")).contains(IvrTreeValidator.SELF_LOOP);
        }


    }

    @Nested
    class CyclesAndReachability {

        @Test
        @DisplayName("VAL-14: a two-node cycle is reported")
        void twoNodeCycleReported() {
            node("A", IvrNodeType.MENU);
            node("B", IvrNodeType.MENU);
            edge("A", "1", "B");
            edge("B", "1", "A");
            assertThat(codes("A")).contains(IvrTreeValidator.CYCLE);
        }

        @Test
        @DisplayName("VAL-15: a three-node cycle is reported")
        void threeNodeCycleReported() {
            node("A", IvrNodeType.MENU);
            node("B", IvrNodeType.MENU);
            node("C", IvrNodeType.MENU);
            edge("A", "1", "B");
            edge("B", "1", "C");
            edge("C", "1", "A");
            assertThat(codes("A")).contains(IvrTreeValidator.CYCLE);
        }

        @Test
        @DisplayName("VAL-16: an unreachable node is reported")
        void unreachableNodeReported() {
            node("ROOT", IvrNodeType.MENU);
            node("ORPHAN", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "LEAF");
            assertThat(codes("ROOT")).contains(IvrTreeValidator.UNREACHABLE);
        }

        @Test
        @DisplayName("VAL-17: a menu with no transitions is reported as a dead end")
        void menuWithoutTransitionsReported() {
            node("ROOT", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "LEAF");
            node("DEAD", IvrNodeType.MENU); // reachable only by being unreachable
            assertThat(codes("ROOT"))
                    .contains(IvrTreeValidator.MENU_WITHOUT_TRANSITIONS);
        }
    }

    @Nested
    class NodeTypeRules {

        @Test
        @DisplayName("VAL-18: a terminal node without an action is reported")
        void terminalWithoutActionReported() {
            node("ROOT", IvrNodeType.MENU);
            IvrNode leaf = node("LEAF", IvrNodeType.TERMINAL);
            leaf.setTerminalAction(null);
            edge("ROOT", "1", "LEAF");
            assertThat(codes("ROOT")).contains(IvrTreeValidator.TERMINAL_WITHOUT_ACTION);
        }

        @Test
        @DisplayName("VAL-19: a terminal node with transitions is reported")
        void terminalWithTransitionsReported() {
            node("ROOT", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            node("OTHER", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "LEAF");
            edge("LEAF", "1", "OTHER");
            assertThat(codes("ROOT")).contains(IvrTreeValidator.TERMINAL_WITH_TRANSITIONS);
        }

        @Test
        @DisplayName("VAL-20: a menu declaring a terminal action is reported")
        void menuWithActionReported() {
            node("ROOT", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "LEAF");
            nodes.get("ROOT").setTerminalAction(IvrTerminalAction.TERMINATE);
            assertThat(codes("ROOT")).contains(IvrTreeValidator.MENU_WITH_ACTION);
        }
    }

    @Nested
    class Bounds {

        @ParameterizedTest(name = "VAL-21: inputWaitSeconds={0} is accepted")
        @ValueSource(ints = {1, 5, 10, 60, 120})
        @DisplayName("VAL-21: the wait range matches the existing DTMF window")
        void validWaitsAccepted(int wait) {
            node("ROOT", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "LEAF");
            nodes.get("ROOT").setInputWaitSeconds(wait);
            assertThat(validate("ROOT")).isEmpty();
        }

        @ParameterizedTest(name = "VAL-22: inputWaitSeconds={0} is rejected")
        @ValueSource(ints = {0, -1, 121, 100000})
        @DisplayName("VAL-22: an out-of-range wait is reported")
        void invalidWaitsRejected(int wait) {
            node("ROOT", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "LEAF");
            nodes.get("ROOT").setInputWaitSeconds(wait);
            assertThat(codes("ROOT")).contains(IvrTreeValidator.INVALID_WAIT);
        }

        @Test
        @DisplayName("VAL-23: a null wait is accepted and means 'use the platform default'")
        void nullWaitAccepted() {
            node("ROOT", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "LEAF");
            nodes.get("ROOT").setInputWaitSeconds(null);
            assertThat(validate("ROOT")).isEmpty();
        }

        @ParameterizedTest(name = "VAL-24: retries={0} is rejected")
        @ValueSource(ints = {-1, 11, 999})
        @DisplayName("VAL-24: out-of-range retry counts are reported")
        void invalidRetriesRejected(int retries) {
            node("ROOT", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "LEAF");
            nodes.get("ROOT").setInvalidInputRetries(retries);
            assertThat(codes("ROOT")).contains(IvrTreeValidator.INVALID_RETRIES);
        }
    }

    @Nested
    class Determinism {

        @Test
        @DisplayName("VAL-25: the same tree always produces the same violations")
        void validationIsDeterministic() {
            node("A", IvrNodeType.MENU);
            node("B", IvrNodeType.MENU);
            edge("A", "1", "B");
            edge("B", "1", "A");

            List<String> first = codes("A");
            for (int i = 0; i < 5; i++) {
                assertThat(codes("A"))
                        .as("a validator whose answer drifted would make the persisted code "
                                + "depend on timing")
                        .isEqualTo(first);
            }
        }

        @Test
        @DisplayName("VAL-26: an empty tree is reported rather than treated as valid")
        void emptyTreeReported() {
            assertThat(IvrTreeValidator.validate("ROOT", List.of(), List.of()))
                    .extracting(IvrTreeValidator.Violation::code)
                    .contains(IvrTreeValidator.EMPTY_TREE);
        }

        @ParameterizedTest(name = "VAL-27: a self loop on {0} is caught")
        @CsvSource({"1", "#", "*"})
        @DisplayName("VAL-27: a self loop is caught for every digit, not just digits")
        void selfLoopCaughtForEveryDigit(String digit) {
            node("ROOT", IvrNodeType.MENU);
            node("LEAF", IvrNodeType.TERMINAL);
            edge("ROOT", "1", "LEAF");
            selfTransition("ROOT", digit);
            assertThat(codes("ROOT")).contains(IvrTreeValidator.SELF_LOOP);
        }
    }
}
