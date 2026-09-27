package com.shivang.obd.ivr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.voice.ivr.IvrNode;
import com.shivang.obd.voice.ivr.IvrNodeRepository;
import com.shivang.obd.voice.ivr.IvrTransition;
import com.shivang.obd.voice.ivr.IvrTransitionRepository;
import com.shivang.obd.voice.ivr.IvrTree;
import com.shivang.obd.voice.ivr.IvrTreeRepository;
import com.shivang.obd.voice.ivr.IvrTreeStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VB-6F — the database guarantees, against real PostgreSQL.
 *
 * <p>The brief asks that tree integrity be enforced by constraints "where
 * practical", and separately that a cross-tree transition be impossible. These
 * tests prove the enforcement is in the schema, not only in Java: each case
 * attempts a write that validation would reject and asserts the <em>database</em>
 * refuses it. A validation-only guarantee would still permit the row through any
 * other writer.
 */
@SpringBootTest
@ActiveProfiles("dev")
class IvrPostgresConstraintTest {

    /**
     * Deliberately NOT {@code @Transactional}.
     * <p>
     * A constraint violation is only raised when a transaction commits, so a
     * test that wrapped each probe in a nested transaction would see nothing:
     * the inner transaction would join the outer one and the violation would
     * surface at the outer commit, outside the assertion. Fixtures are
     * therefore committed and torn down explicitly in {@link #cleanup()}.
     */

    @Autowired
    private IvrTreeRepository treeRepository;
    @Autowired
    private IvrNodeRepository nodeRepository;
    @Autowired
    private IvrTransitionRepository transitionRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private com.shivang.obd.tenant.TenantRepository tenantRepository;

    private UUID tenantA;
    private UUID tenantB;
    private IvrTree treeA;
    private IvrTree treeB;

    private void seed() {
        transactionTemplate.executeWithoutResult(tx -> {
        tenantA = createTenant("A");
        tenantB = createTenant("B");

        treeA = new IvrTree();
        treeA.setTenantId(tenantA);
        treeA.setName("Tree A");
        treeA.setStatus(IvrTreeStatus.ACTIVE);
        treeRepository.save(treeA);

        treeB = new IvrTree();
        treeB.setTenantId(tenantB);
        treeB.setName("Tree B");
        treeB.setStatus(IvrTreeStatus.ACTIVE);
        treeRepository.save(treeB);
        });
    }

    /** Removes everything the test created, in dependency order. */
    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        if (tenantA == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            for (IvrTree tree : treeRepository.findAll()) {
                if (tree.getTenantId().equals(tenantA) || tree.getTenantId().equals(tenantB)) {
                    transitionRepository.deleteByTreeIdAndDeletedAtIsNull(tree.getId());
                    nodeRepository.deleteAll(
                            nodeRepository.findByTreeIdAndDeletedAtIsNullOrderByNodeKeyAsc(
                                    tree.getId()));
                    treeRepository.delete(tree);
                }
            }
            tenantRepository.deleteAll(tenantRepository.findAll().stream()
                    .filter(t -> t.getId().equals(tenantA) || t.getId().equals(tenantB))
                    .toList());
        });
    }

    /**
     * A tenant is created through its repository so it joins the test
     * transaction and rolls back with it. A raw-connection insert would commit
     * outside the transaction, leak rows, and collide on the unique slug slug
     * across tests.
     */
    private UUID createTenant(String suffix) {
        com.shivang.obd.tenant.TenantEntity tenant = new com.shivang.obd.tenant.TenantEntity();
        // The id is left to the database: TenantEntity is versioned, so
        // assigning one would turn the save into a merge against a row that does
        // not exist yet.
        tenant.setName("IVR Test Tenant " + suffix);
        // The slug is unique platform-wide, so it carries the tenant's own id to
        // stay collision-free across repeated runs.
        tenant.setSlug("ivr-test-" + suffix + "-" + UUID.randomUUID());
        tenant.setStatus(com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE);
        return tenantRepository.saveAndFlush(tenant).getId();
    }



    private IvrNode node(IvrTree tree, String key) {
        IvrNode node = new IvrNode();
        node.setTreeId(tree.getId());
        node.setNodeKey(key);
        node.setNodeType(com.shivang.obd.voice.ivr.IvrNodeType.MENU);
        node.setInputWaitSeconds(10);
        node.setInvalidInputRetries(0);
        node.setNoInputRetries(0);
        return nodeRepository.save(node);
    }

    private IvrNode terminal(IvrTree tree, String key) {
        IvrNode node = new IvrNode();
        node.setTreeId(tree.getId());
        node.setNodeKey(key);
        node.setNodeType(com.shivang.obd.voice.ivr.IvrNodeType.TERMINAL);
        node.setInputWaitSeconds(10);
        node.setInvalidInputRetries(0);
        node.setNoInputRetries(0);
        node.setTerminalAction(com.shivang.obd.voice.ivr.IvrTerminalAction.TERMINATE);
        return nodeRepository.save(node);
    }

    private IvrTransition transition(IvrTree tree, IvrNode from, String input, IvrNode to) {
        IvrTransition transition = new IvrTransition();
        transition.setTreeId(tree.getId());
        transition.setNodeId(from.getId());
        transition.setDtmfInput(input);
        transition.setTargetNodeId(to.getId());
        return transitionRepository.save(transition);
    }

    /** Runs a write in its own transaction so a constraint violation surfaces. */
    private void assertRejected(Runnable write, String because) {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> write.run()))
                .as(because)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // =====================================================================
    // Example 8 — cross-tree transitions
    // =====================================================================

    @Test
    @DisplayName("PG-IVR-1: a transition into another tree is UNREPRESENTABLE, not merely rejected")
    void crossTreeTransitionIsUnrepresentable() {
        seed();
        IvrNode rootA = node(treeA, "ROOT");
        IvrNode leafA = terminal(treeA, "LEAF");
        IvrNode leafB = terminal(treeB, "LEAF_B");

        // A within-tree transition is fine.
        transition(treeA, rootA, "1", leafA);

        // Pointing at the other tenant's tree is refused by the composite FK.
        assertRejected(() -> transition(treeA, rootA, "2", leafB),
                "a transition must not be able to reference a node in another tree");
    }

    @Test
    @DisplayName("PG-IVR-2: the same digit cannot map to two transitions on one node")
    void duplicateDigitIsUnrepresentable() {
        seed();
        IvrNode rootA = node(treeA, "ROOT");
        IvrNode one = terminal(treeA, "ONE");
        IvrNode two = terminal(treeA, "TWO");

        transition(treeA, rootA, "1", one);

        assertRejected(() -> transition(treeA, rootA, "1", two),
                "one digit must map to exactly one target per node");
    }

    @Test
    @DisplayName("PG-IVR-3: the same node key cannot appear twice in a tree")
    void duplicateNodeKeyIsUnrepresentable() {
        seed();
        node(treeA, "ROOT");

        assertRejected(() -> node(treeA, "ROOT"),
                "a node key must be unique within its tree");
    }

    @Test
    @DisplayName("PG-IVR-4: the same node key MAY appear in two different trees")
    void sameKeyInDifferentTreesIsAllowed() {
        // The uniqueness is per tree, so two tenants can both have a "ROOT".
        seed();
        node(treeA, "ROOT");
        node(treeB, "ROOT");

        assertThat(nodeRepository.findByTreeIdAndNodeKeyAndDeletedAtIsNull(
                treeA.getId(), "ROOT")).isNotEmpty();
        assertThat(nodeRepository.findByTreeIdAndNodeKeyAndDeletedAtIsNull(
                treeB.getId(), "ROOT")).isNotEmpty();
    }

    @Test
    @DisplayName("PG-IVR-5: a digit outside 0-9/*/# is refused")
    void invalidDigitIsUnrepresentable() {
        seed();
        IvrNode rootA = node(treeA, "ROOT");
        IvrNode leafA = terminal(treeA, "LEAF");

        assertRejected(() -> transition(treeA, rootA, "A", leafA),
                "the DTMF alphabet must be enforced by the schema");
    }

    @Test
    @DisplayName("PG-IVR-6: a self-pointing transition is refused")
    void selfLoopIsUnrepresentable() {
        seed();
        IvrNode rootA = node(treeA, "ROOT");

        assertRejected(() -> transition(treeA, rootA, "1", rootA),
                "a node must not be able to point at itself");
    }

    @Test
    @DisplayName("PG-IVR-7: an out-of-range input wait is refused")
    void invalidWaitIsUnrepresentable() {
        seed();
        IvrNode node = new IvrNode();
        node.setTreeId(treeA.getId());
        node.setNodeKey("BAD_WAIT");
        node.setNodeType(com.shivang.obd.voice.ivr.IvrNodeType.MENU);
        node.setInputWaitSeconds(500);
        node.setInvalidInputRetries(0);
        node.setNoInputRetries(0);

        assertRejected(() -> nodeRepository.save(node),
                "the wait range must be enforced by the schema");
    }

    @Test
    @DisplayName("PG-IVR-8: a negative retry count is refused")
    void negativeRetriesUnrepresentable() {
        seed();
        IvrNode node = new IvrNode();
        node.setTreeId(treeA.getId());
        node.setNodeKey("BAD_RETRY");
        node.setNodeType(com.shivang.obd.voice.ivr.IvrNodeType.MENU);
        node.setInputWaitSeconds(10);
        node.setInvalidInputRetries(-1);
        node.setNoInputRetries(0);

        assertRejected(() -> nodeRepository.save(node),
                "retry counts must be enforced by the schema");
    }

    @Test
    @DisplayName("PG-IVR-9: a tree's root must be a node of that same tree")
    void rootMustBelongToItsTree() {
        seed();
        IvrNode leafA = terminal(treeA, "LEAF");
        IvrNode leafB = terminal(treeB, "LEAF_B");

        // treeB.rootNodeId = a node of treeA is refused.
        assertRejected(() -> {
            IvrTree update = treeRepository.findById(treeB.getId()).orElseThrow();
            update.setRootNodeId(leafA.getId());
            treeRepository.saveAndFlush(update);
        }, "a tree's root must belong to that tree");
    }

    @Test
    @DisplayName("PG-IVR-10: a valid two-level tree persists and reads back intact")
    void validTreeRoundTrips() {
        seed();
        IvrNode root = node(treeA, "ROOT");
        IvrNode sales = node(treeA, "SALES");
        IvrNode leaf = terminal(treeA, "LEAF");
        transition(treeA, root, "1", sales);
        transition(treeA, sales, "2", leaf);
        treeA.setRootNodeId(root.getId());
        treeRepository.saveAndFlush(treeA);

        List<IvrNode> nodes =
                nodeRepository.findByTreeIdAndDeletedAtIsNullOrderByNodeKeyAsc(treeA.getId());
        assertThat(nodes).extracting(IvrNode::getNodeKey)
                .containsExactly("LEAF", "ROOT", "SALES");
        assertThat(transitionRepository.findByTreeIdAndDeletedAtIsNullOrderByIdAsc(treeA.getId()))
                .hasSize(2);
    }
}
