package com.shivang.obd.campaign;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.ivr.IvrTreeService;
import com.shivang.obd.voice.ivr.IvrExecutionSnapshot;
import com.shivang.obd.voice.ivr.IvrNode;
import com.shivang.obd.voice.ivr.IvrNodeSnapshot;
import com.shivang.obd.voice.ivr.IvrTransition;
import com.shivang.obd.voice.ivr.IvrTree;
import com.shivang.obd.voice.ivr.IvrTreeValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Captures a live IVR tree into an immutable execution snapshot (VB-6F).
 *
 * <h2>Where the snapshot goes</h2>
 *
 * <p>Into the <em>existing</em> {@code type_config} JSONB of the execution's
 * configuration snapshot, under an {@code "ivr"} key. That column is already
 * written once and {@code updatable = false}, so reusing it means VB-6F needs no
 * snapshot table, no IVR versioning and no history — the immutability guarantee
 * VB-6A established for every other execution-affecting field applies to the IVR
 * for free.
 *
 * <h2>What is captured</h2>
 *
 * <p>The tree is resolved to a <em>flat, keyed</em> value: nodes in a map by
 * {@code nodeKey}, transitions as digit to target <b>key</b>. Runtime traversal
 * is then a map lookup with no live IVR read at all, which is the whole point —
 * editing a live tree cannot move a caller who is already in the old flow.
 *
 * <h2>What is not captured</h2>
 *
 * <p>Prompt assets are frozen as identities. Ownership and approval stay
 * dynamic, checked at playback time through the existing resource authority.
 * That is the VB-6A §9 split, preserved rather than reinvented: the snapshot
 * pins <em>what was requested</em>, governance decides whether it may still be
 * used.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IvrSnapshotCapture {

    private final IvrTreeService treeService;
    private final CampaignIvrPromptGovernance promptGovernance;

    /**
     * Builds the execution snapshot for a tree.
     *
     * <p>Validates structure and prompt resources first, so a call never starts
     * against a tree that could not be executed. Both checks run here rather than
     * only at activation because a resource can be revoked between the two.
     *
     * @param treeId   the tree to freeze
     * @param tenantId the owning tenant, which must own the tree
     * @throws BusinessException when the tree is not selectable, is structurally
     *         invalid, or has an unusable prompt
     */
    public IvrExecutionSnapshot capture(UUID treeId, UUID tenantId) {
        IvrTree tree = treeService.requireSelectable(treeId, tenantId);
        List<IvrNode> nodes = treeService.loadNodes(tree.getId());
        List<IvrTransition> transitions = treeService.loadTransitions(tree.getId());

        String rootKey = null;
        for (IvrNode node : nodes) {
            if (node.getId().equals(tree.getRootNodeId())) {
                rootKey = node.getNodeKey();
            }
        }
        if (rootKey == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                    "IVR tree " + treeId + " has no root node and cannot be executed.");
        }

        List<IvrTreeValidator.Violation> violations =
                IvrTreeValidator.validate(rootKey, nodes, transitions);
        if (!violations.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (IvrTreeValidator.Violation violation : violations) {
                if (detail.length() > 0) {
                    detail.append("; ");
                }
                detail.append(violation.message());
            }
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                    "IVR tree " + treeId + " cannot be executed: " + detail);
        }

        promptGovernance.requireUsablePrompts(nodes, tenantId);

        Map<UUID, String> keysById = new LinkedHashMap<>();
        for (IvrNode node : nodes) {
            keysById.put(node.getId(), node.getNodeKey());
        }

        Map<UUID, List<IvrTransition>> edgesByNode = new LinkedHashMap<>();
        for (IvrTransition transition : transitions) {
            edgesByNode.computeIfAbsent(transition.getNodeId(), k -> new ArrayList<>())
                    .add(transition);
        }

        // Nodes are already in nodeKey order, so the snapshot is deterministic:
        // the same tree always produces byte-identical JSON.
        Map<String, IvrNodeSnapshot> byKey = new LinkedHashMap<>();
        for (IvrNode node : nodes) {
            Map<String, String> edges = new LinkedHashMap<>();
            for (IvrTransition transition : edgesByNode.getOrDefault(node.getId(), List.of())) {
                edges.put(transition.getDtmfInput(), keysById.get(transition.getTargetNodeId()));
            }
            byKey.put(node.getNodeKey(), new IvrNodeSnapshot(
                    node.getNodeKey(),
                    node.getNodeType(),
                    node.getPromptAudioAssetId(),
                    node.getInputWaitSeconds() == null
                            ? IvrTreeService.DEFAULT_INPUT_WAIT_SECONDS
                            : node.getInputWaitSeconds(),
                    node.getInvalidPromptAudioAssetId(),
                    node.getInvalidInputRetries() == null ? 0 : node.getInvalidInputRetries(),
                    node.getNoInputPromptAudioAssetId(),
                    node.getNoInputRetries() == null ? 0 : node.getNoInputRetries(),
                    node.getTerminalAction(),
                    edges));
        }

        IvrExecutionSnapshot snapshot =
                new IvrExecutionSnapshot(tree.getId(), tree.getName(), rootKey, byKey);
        log.info("IVR execution snapshot captured (tree={}, tenant={}, nodes={}, root={})",
                tree.getId(), tenantId, byKey.size(), rootKey);
        return snapshot;
    }
}
