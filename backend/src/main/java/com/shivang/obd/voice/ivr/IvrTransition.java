package com.shivang.obd.voice.ivr;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One DTMF digit on one {@link IvrNode}, and where it leads (VB-6F).
 * <p>
 * <b>Why this row carries {@code treeId} even though its node already does.</b>
 * It is deliberate redundancy, and it is the mechanism that makes cross-tree
 * navigation unrepresentable rather than merely rejected in Java. V53 declares
 * two composite foreign keys against {@code ivr_nodes(id, tree_id)}:
 *
 * <pre>
 *   FOREIGN KEY (node_id,        tree_id) REFERENCES ivr_nodes (id, tree_id)
 *   FOREIGN KEY (target_node_id, tree_id) REFERENCES ivr_nodes (id, tree_id)
 * </pre>
 *
 * Because both endpoints are pinned to the same {@code tree_id}, a transition
 * whose target lives in another tree violates the second FK and the row cannot
 * be written at all. The brief requires that "tree A node must not point at tree
 * B"; this is how that is guaranteed rather than merely validated.
 * <p>
 * {@code UQ (node_id, dtmf_input)} is the second half of "the same DTMF input
 * cannot map to multiple transitions", enforced by the database as well as by
 * the validator. The CHECK constraint restricts the digit to the same alphabet
 * the existing runtime accepts, {@code 0-9 * #}, so no new DTMF semantics are
 * introduced.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ivr_transitions", indexes = {
    @Index(name = "idx_ivr_transitions_node", columnList = "node_id, deleted_at"),
    @Index(name = "idx_ivr_transitions_target", columnList = "target_node_id, deleted_at")
})
public class IvrTransition extends AuditableEntity {

    /** Owning tree. Redundant with the parent node's, and the basis of the same-tree FKs. */
    @Column(name = "tree_id", nullable = false)
    private UUID treeId;

    /** The node whose question this digit answers. */
    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    /** A single DTMF character: {@code 0-9}, {@code *} or {@code #}. */
    @Column(name = "dtmf_input", nullable = false, length = 1)
    private String dtmfInput;

    /** The node reached. Constrained by V53 to belong to {@link #treeId}. */
    @Column(name = "target_node_id", nullable = false)
    private UUID targetNodeId;
}
