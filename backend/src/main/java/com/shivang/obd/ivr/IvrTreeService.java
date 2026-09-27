package com.shivang.obd.ivr;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.authz.context.OrganizationContext;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.ivr.dto.ChangeIvrTreeStatusRequest;
import com.shivang.obd.ivr.dto.CreateIvrTreeRequest;
import com.shivang.obd.ivr.dto.IvrNodeRequest;
import com.shivang.obd.ivr.dto.IvrTransitionRequest;
import com.shivang.obd.ivr.dto.IvrTreeResponse;
import com.shivang.obd.ivr.dto.UpdateIvrTreeRequest;
import com.shivang.obd.voice.ivr.IvrNode;
import com.shivang.obd.voice.ivr.IvrNodeRepository;
import com.shivang.obd.voice.ivr.IvrNodeType;
import com.shivang.obd.voice.ivr.IvrTerminalAction;
import com.shivang.obd.voice.ivr.IvrTransition;
import com.shivang.obd.voice.ivr.IvrTransitionRepository;
import com.shivang.obd.voice.ivr.IvrTree;
import com.shivang.obd.voice.ivr.IvrTreeRepository;
import com.shivang.obd.voice.ivr.IvrTreeStatus;
import com.shivang.obd.voice.ivr.IvrTreeValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The authority for reusable IVR trees (VB-6F).
 *
 * <h2>One write shape</h2>
 *
 * <p>Every mutation replaces the tree's whole contents in one transaction and
 * validates the result as a whole. There are no per-node endpoints, because a
 * tree is only meaningful as a complete structure and per-node writes would let
 * a caller persist â€” and then activate â€” a tree with two roots, a dangling
 * transition or an unreachable node.
 *
 * <h2>Validation happens before anything can be dialled</h2>
 *
 * <p>{@link IvrTreeValidator} is run on create, on update, and again on
 * activation. Create and update reject an invalid tree outright; activation
 * re-checks because a tree may have been valid when written and its <em>prompt
 * assets</em> may since have lost approval. Nothing invalid is ever captured
 * into an execution snapshot.
 *
 * <h2>Module position</h2>
 *
 * <p>This is a resource module in the shape of {@code tts} and {@code audio}:
 * it depends on the {@code voice} domain and on {@code authz}, and knows nothing
 * about campaigns. The campaign side (attaching a tree, capturing a snapshot,
 * running the flow) lives in {@code campaign} and depends on this module, which
 * is the only direction that keeps the graph acyclic.
 *
 * <h2>Tenant isolation</h2>
 *
 * <p>Every read is scoped by {@code (id, tenantId)} resolved from the caller's
 * organizational boundary, so a foreign tree and a nonexistent tree are
 * indistinguishable â€” the platform-wide convention, and the reason a
 * cross-tenant probe cannot even confirm that an id exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IvrTreeService {

    /** View a tree and its structure. */
    public static final String CAP_VIEW = "IVR_VIEW";

    /** Create, edit, activate, archive and delete a tree. */
    public static final String CAP_MANAGE = "IVR_MANAGE";

    /** Runtime default when a node omits an input wait. Matches the DTMF default. */
    public static final int DEFAULT_INPUT_WAIT_SECONDS = 10;

    private final IvrTreeRepository treeRepository;
    private final IvrNodeRepository nodeRepository;
    private final IvrTransitionRepository transitionRepository;
    private final AuthorizationService authorizationService;
    /**
     * Prompt-resource governance, supplied by the campaign side through the
     * {@link IvrPromptChecker} port so this module stays free of a campaign
     * dependency. Optional: a deployment with no campaign layer can still
     * author and activate trees, and structural validation still applies.
     */
    private final org.springframework.beans.factory.ObjectProvider<IvrPromptChecker> promptChecker;

    // =====================================================================
    // Create
    // =====================================================================

    /**
     * Creates a tree in {@code DRAFT}, with its full structure.
     *
     * <p>Draft rather than active, so a tree is never dialled before an operator
     * has had a chance to see it. The structure is validated on the way in.
     */
    @Transactional
    public ApiResponse<IvrTreeResponse> create(CreateIvrTreeRequest request) {
        UUID tenantId = requireTenant();
        UUID userId = requireUser();
        authorizationService.requireCapability(userId, CAP_MANAGE,
                com.shivang.obd.authz.AccessCheck.forTenant(tenantId));

        IvrTree tree = new IvrTree();
        tree.setTenantId(tenantId);
        tree.setName(request.name().trim());
        tree.setDescription(request.description());
        tree.setStatus(IvrTreeStatus.DRAFT);
        treeRepository.save(tree);

        writeContents(tree, request.rootNodeKey(), request.nodes(), tenantId);

        log.info("IVR tree created (tenant={}, tree={}, root={}, nodes={})",
                tenantId, tree.getId(), request.rootNodeKey(), request.nodes().size());
        return ResponseFactory.created(toResponse(tree));
    }

    // =====================================================================
    // Read
    // =====================================================================

    /** Tenant-scoped read. A foreign tree is a 404, never a 403. */
    @Transactional(readOnly = true)
    public ApiResponse<IvrTreeResponse> getById(UUID id) {
        UUID tenantId = requireTenant();
        IvrTree tree = findVisible(id, tenantId);
        authorizationService.requireCapability(requireUser(), CAP_VIEW,
                com.shivang.obd.authz.AccessCheck.forTenant(tree.getTenantId()));
        return ResponseFactory.ok(toResponse(tree));
    }

    /** A tenant's trees, newest first. */
    @Transactional(readOnly = true)
    public ApiResponse<List<IvrTreeResponse>> list(int page, int size, String status) {
        UUID tenantId = requireTenant();
        authorizationService.requireCapability(requireUser(), CAP_VIEW,
                com.shivang.obd.authz.AccessCheck.forTenant(tenantId));

        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        var pageable = org.springframework.data.domain.PageRequest.of(
                safePage, safeSize, org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));

        List<IvrTree> trees = (status == null || status.isBlank())
                ? treeRepository.findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        tenantId, pageable)
                : treeRepository.findByTenantIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                        tenantId, parseStatus(status), pageable);

        return ResponseFactory.ok(trees.stream().map(this::toResponse).toList());
    }

    // =====================================================================
    // Update
    // =====================================================================

    /**
     * Replaces the whole structure of a tree.
     *
     * <p>Running executions are unaffected: each captured its own immutable
     * snapshot, so a caller already in the old flow keeps it and the change
     * applies to executions created afterwards.
     */
    @Transactional
    public ApiResponse<IvrTreeResponse> update(UUID id, UpdateIvrTreeRequest request) {
        UUID tenantId = requireTenant();
        IvrTree tree = findVisible(id, tenantId);
        authorizationService.requireCapability(requireUser(), CAP_MANAGE,
                com.shivang.obd.authz.AccessCheck.forTenant(tenantId));

        if (tree.getStatus() == IvrTreeStatus.ARCHIVED) {
            throw business("An archived IVR tree cannot be edited.");
        }

        writeContents(tree, request.rootNodeKey(), request.nodes(), tenantId);
        log.info("IVR tree replaced (tenant={}, tree={}, nodes={})",
                tenantId, tree.getId(), request.nodes().size());
        return ResponseFactory.ok(toResponse(tree));
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    /**
     * Moves a tree through its lifecycle.
     *
     * <p>Activation re-validates structure <em>and</em> prompt resources. The
     * re-check is not redundant: a tree written while its audio assets were
     * approved can be invalid by activation time, and only ACTIVE trees may be
     * captured into an execution snapshot.
     */
    @Transactional
    public ApiResponse<IvrTreeResponse> changeStatus(UUID id, ChangeIvrTreeStatusRequest request) {
        UUID tenantId = requireTenant();
        IvrTree tree = findVisible(id, tenantId);
        authorizationService.requireCapability(requireUser(), CAP_MANAGE,
                com.shivang.obd.authz.AccessCheck.forTenant(tenantId));

        IvrTreeResponse.IvrTreeStatusDto target = request.status();
        switch (target) {
            case ACTIVE -> {
                requireDraft(tree);
                validateForActivation(tree, tenantId);
                tree.setStatus(IvrTreeStatus.ACTIVE);
            }
            case ARCHIVED -> {
                if (tree.getStatus() == IvrTreeStatus.ARCHIVED) {
                    throw business("This IVR tree is already archived.");
                }
                tree.setStatus(IvrTreeStatus.ARCHIVED);
            }
            case DRAFT -> throw business(
                    "An IVR tree cannot return to DRAFT. Create a new tree instead, so existing "
                    + "execution snapshots keep the flow they were created with.");
            default -> throw business("Unsupported IVR tree status: " + target);
        }

        treeRepository.save(tree);
        log.info("IVR tree status changed (tenant={}, tree={}, status={})",
                tenantId, tree.getId(), tree.getStatus());
        return ResponseFactory.ok(toResponse(tree));
    }

    /**
     * Soft-deletes a tree.
     *
     * <p>Not a hard delete: execution snapshots are self-contained, and
     * historical calls must remain auditable. Archived is the preferred way to
     * retire a tree; delete exists for a tree that was never used.
     */
    @Transactional
    public void delete(UUID id) {
        UUID tenantId = requireTenant();
        IvrTree tree = findVisible(id, tenantId);
        authorizationService.requireCapability(requireUser(), CAP_MANAGE,
                com.shivang.obd.authz.AccessCheck.forTenant(tenantId));

        nodeRepository.deleteAll(nodeRepository.findByTreeIdAndDeletedAtIsNullOrderByNodeKeyAsc(
                tree.getId()));
        transitionRepository.deleteByTreeIdAndDeletedAtIsNull(tree.getId());
        tree.setDeletedAt(java.time.Instant.now());
        treeRepository.save(tree);
        log.info("IVR tree deleted (tenant={}, tree={})", tenantId, tree.getId());
    }

    // =====================================================================
    // Internals used by the campaign side
    // =====================================================================

    /**
     * Records which campaign a tree was derived from.
     * <p>
     * Purely informational: the campaign's own typeConfig is the authority for
     * what that campaign now references, and nothing reverses a conversion from
     * this field. It exists so an operator can see where a tree came from.
     */
    @Transactional
    public void recordProvenance(UUID treeId, UUID campaignId) {
        UUID tenantId = requireTenant();
        IvrTree tree = findVisible(treeId, tenantId);
        authorizationService.requireCapability(requireUser(), CAP_MANAGE,
                com.shivang.obd.authz.AccessCheck.forTenant(tenantId));
        tree.setSourceCampaignId(campaignId);
        treeRepository.save(tree);
    }

    /**
     * The tree as the campaign side needs it: an {@code ACTIVE} tree of this
     * tenant, or a validation failure naming the reason.
     * <p>
     * Used when attaching a tree to a campaign and when capturing a snapshot, so
     * "is this tree usable" has exactly one answer everywhere.
     */
    @Transactional(readOnly = true)
    public IvrTree requireSelectable(UUID treeId, UUID tenantId) {
        IvrTree tree = treeRepository.findByIdAndTenantIdAndDeletedAtIsNull(treeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("IVR tree not found"));
        if (!tree.selectable()) {
            throw business("IVR tree " + treeId + " is " + tree.getStatus()
                    + " and cannot be selected for execution; only an ACTIVE tree may be used.");
        }
        return tree;
    }

    /** A tree's live nodes, in stable key order so a snapshot is deterministic. */
    @Transactional(readOnly = true)
    public List<IvrNode> loadNodes(UUID treeId) {
        return nodeRepository.findByTreeIdAndDeletedAtIsNullOrderByNodeKeyAsc(treeId);
    }

    /** A tree's live transitions. */
    @Transactional(readOnly = true)
    public List<IvrTransition> loadTransitions(UUID treeId) {
        return transitionRepository.findByTreeIdAndDeletedAtIsNullOrderByIdAsc(treeId);
    }

    // =====================================================================
    // Content writing
    // =====================================================================

    /**
     * Writes a tree's complete contents: nodes first (so transitions have
     * targets to reference), then transitions, then the root pointer.
     */
    private void writeContents(IvrTree tree, String rootNodeKey, List<IvrNodeRequest> nodes,
                               UUID tenantId) {
        // Replace wholesale. Transitions first, because V53's composite FKs
        // require both endpoints to exist.
        transitionRepository.deleteByTreeIdAndDeletedAtIsNull(tree.getId());
        nodeRepository.deleteAll(
                nodeRepository.findByTreeIdAndDeletedAtIsNullOrderByNodeKeyAsc(tree.getId()));

        Map<String, IvrNode> byKey = new LinkedHashMap<>();
        for (IvrNodeRequest request : nodes) {
            IvrNode node = new IvrNode();
            node.setTreeId(tree.getId());
            node.setNodeKey(request.nodeKey());
            node.setNodeType(request.nodeType());
            node.setPromptAudioAssetId(request.promptAudioAssetId());
            node.setInvalidPromptAudioAssetId(request.invalidPromptAudioAssetId());
            node.setNoInputPromptAudioAssetId(request.noInputPromptAudioAssetId());
            node.setInputWaitSeconds(request.inputWaitSeconds() == null
                    ? DEFAULT_INPUT_WAIT_SECONDS : request.inputWaitSeconds());
            node.setInvalidInputRetries(request.invalidInputRetries() == null
                    ? 0 : request.invalidInputRetries());
            node.setNoInputRetries(request.noInputRetries() == null
                    ? 0 : request.noInputRetries());
            node.setTerminalAction(request.terminalAction());
            nodeRepository.save(node);
            byKey.put(node.getNodeKey(), node);
        }

        for (IvrNodeRequest request : nodes) {
            IvrNode source = byKey.get(request.nodeKey());
            for (IvrTransitionRequest edge : nullSafe(request.transitions())) {
                IvrNode target = byKey.get(edge.targetNodeKey());
                if (target == null) {
                    // Reported by the validator too, but failing here keeps the
                    // write atomic instead of relying on a later check.
                    throw business("Transition target '" + edge.targetNodeKey()
                            + "' from node '" + source.getNodeKey()
                            + "' is not a node of this tree. IVR trees cannot reference "
                            + "another tree.");
                }
                IvrTransition transition = new IvrTransition();
                transition.setTreeId(tree.getId());
                transition.setNodeId(source.getId());
                transition.setDtmfInput(edge.input());
                transition.setTargetNodeId(target.getId());
                transitionRepository.save(transition);
            }
        }

        IvrNode root = byKey.get(rootNodeKey);
        if (root == null) {
            throw business("Root node '" + rootNodeKey
                    + "' is not among the tree's nodes. An IVR tree needs exactly one root.");
        }
        tree.setRootNodeId(root.getId());
        treeRepository.save(tree);

        // Validate the persisted shape as a whole, now that it is real.
        List<IvrTreeValidator.Violation> violations = IvrTreeValidator.validate(
                rootNodeKey,
                loadNodes(tree.getId()),
                loadTransitions(tree.getId()));
        if (!violations.isEmpty()) {
            throw business("IVR tree is not valid: " + describe(violations));
        }
    }

    /** Structure plus prompt-resource governance â€” the gate before a tree may be used. */
    private void validateForActivation(IvrTree tree, UUID tenantId) {
        List<IvrNode> nodes = loadNodes(tree.getId());
        List<IvrTransition> transitions = loadTransitions(tree.getId());
        List<IvrTreeValidator.Violation> violations = IvrTreeValidator.validate(
                rootKeyOf(tree, nodes), nodes, transitions);
        if (!violations.isEmpty()) {
            throw business("IVR tree cannot be activated: " + describe(violations));
        }
        promptChecker.ifAvailable(checker -> checker.requireUsablePrompts(nodes, tenantId));
    }

    /**
     * The root's {@code nodeKey}, resolved from the same node list the validator
     * is given.
     * <p>
     * Derived here rather than stored twice, so the id and the key can never
     * disagree, and rather than cached in a lookup structure, which would be
     * both unnecessary and a leak.
     */
    private static String rootKeyOf(IvrTree tree, List<IvrNode> nodes) {
        if (tree.getRootNodeId() == null) {
            return null;
        }
        return nodes.stream()
                .filter(node -> node.getId().equals(tree.getRootNodeId()))
                .map(IvrNode::getNodeKey)
                .findFirst()
                .orElse(null);
    }

    // =====================================================================
    // Mapping
    // =====================================================================

    private IvrTreeResponse toResponse(IvrTree tree) {
        List<IvrNode> nodes =
                nodeRepository.findByTreeIdAndDeletedAtIsNullOrderByNodeKeyAsc(tree.getId());
        List<IvrTransition> transitions =
                transitionRepository.findByTreeIdAndDeletedAtIsNullOrderByIdAsc(tree.getId());
        return toResponse(tree, nodes, transitions);
    }

    private static IvrTreeResponse toResponse(IvrTree tree, List<IvrNode> nodes,
                                              List<IvrTransition> transitions) {
        Map<UUID, String> keysById = new LinkedHashMap<>();
        for (IvrNode node : nodes) {
            keysById.put(node.getId(), node.getNodeKey());
        }

        String rootKey = tree.getRootNodeId() == null ? null : keysById.get(tree.getRootNodeId());

        List<IvrTreeResponse.IvrNodeResponse> nodeResponses = new ArrayList<>();
        for (IvrNode node : nodes) {
            List<IvrTreeResponse.IvrTransitionResponse> edges = new ArrayList<>();
            for (IvrTransition transition : transitions) {
                if (transition.getNodeId().equals(node.getId())) {
                    edges.add(new IvrTreeResponse.IvrTransitionResponse(
                            transition.getDtmfInput(),
                            keysById.get(transition.getTargetNodeId())));
                }
            }
            nodeResponses.add(new IvrTreeResponse.IvrNodeResponse(
                    node.getNodeKey(),
                    node.getNodeType(),
                    node.getPromptAudioAssetId(),
                    node.getInputWaitSeconds(),
                    node.getInvalidPromptAudioAssetId(),
                    node.getInvalidInputRetries(),
                    node.getNoInputPromptAudioAssetId(),
                    node.getNoInputRetries(),
                    node.getTerminalAction(),
                    edges));
        }

        return new IvrTreeResponse(
                tree.getId(),
                tree.getName(),
                tree.getDescription(),
                IvrTreeResponse.IvrTreeStatusDto.valueOf(tree.getStatus().name()),
                rootKey,
                tree.getSourceCampaignId(),
                nodeResponses,
                tree.getCreatedAt(),
                tree.getUpdatedAt());
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private IvrTree findVisible(UUID id, UUID tenantId) {
        return treeRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("IVR tree not found"));
    }

    private static void requireDraft(IvrTree tree) {
        if (tree.getStatus() != IvrTreeStatus.DRAFT) {
            throw business("Only a DRAFT IVR tree can be activated; this one is "
                    + tree.getStatus() + ".");
        }
    }

    private static String describe(List<IvrTreeValidator.Violation> violations) {
        StringBuilder text = new StringBuilder();
        for (IvrTreeValidator.Violation violation : violations) {
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(violation.message());
        }
        return text.toString();
    }

    private static IvrTreeStatus parseStatus(String raw) {
        try {
            return IvrTreeStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw business("Unknown IVR tree status '" + raw
                    + "'. Valid values are DRAFT, ACTIVE, ARCHIVED.");
        }
    }

    private static List<IvrTransitionRequest> nullSafe(List<IvrTransitionRequest> list) {
        return list == null ? List.of() : list;
    }

    private static UUID requireTenant() {
        return OrganizationContextHolder.current()
                .map(OrganizationContext::tenantId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.UNAUTHORIZED,
                        "An IVR tree operation requires an authenticated tenant context"));
    }

    private static UUID requireUser() {
        return OrganizationContextHolder.currentUserId()
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.UNAUTHORIZED,
                        "An IVR tree operation requires an authenticated user"));
    }

    private static BusinessException business(String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_ERROR, message);
    }
}
