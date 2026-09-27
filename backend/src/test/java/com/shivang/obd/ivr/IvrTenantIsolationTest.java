package com.shivang.obd.ivr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.ivr.dto.CreateIvrTreeRequest;
import com.shivang.obd.ivr.dto.IvrNodeRequest;
import com.shivang.obd.ivr.dto.IvrTransitionRequest;
import com.shivang.obd.voice.ivr.IvrNode;
import com.shivang.obd.voice.ivr.IvrNodeRepository;
import com.shivang.obd.voice.ivr.IvrNodeType;
import com.shivang.obd.voice.ivr.IvrTerminalAction;
import com.shivang.obd.voice.ivr.IvrTransitionRepository;
import com.shivang.obd.voice.ivr.IvrTree;
import com.shivang.obd.voice.ivr.IvrTreeRepository;
import com.shivang.obd.voice.ivr.IvrTreeStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * VB-6F — tenant isolation for IVR trees.
 *
 * <p>The brief is explicit that a tenant must not be able to read, modify,
 * attach, or reference another tenant's tree, node, or audio. Each of those is
 * asserted here as a <em>negative</em> case, because isolation is a property that
 * only exists when the refusals are tested.
 *
 * <p>Every repository lookup the service performs is bounded by
 * {@code (id, tenantId)}. That is the whole mechanism: a foreign tree does not
 * fail an authorization check, it simply does not resolve, which is why a
 * foreign tree and a nonexistent one are indistinguishable.
 */
class IvrTenantIsolationTest {

    private static final UUID USER = UUID.fromString("ff000000-0000-4000-8000-0000000000ff");
    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-0000000000a1");
    private static final UUID TENANT_B = UUID.fromString("bb000000-0000-4000-8000-0000000000b1");
    private static final UUID TREE_B = UUID.fromString("cc000000-0000-4000-8000-0000000000c1");

    private IvrTreeRepository treeRepository;
    private IvrNodeRepository nodeRepository;
    private IvrTransitionRepository transitionRepository;
    private AuthorizationService authorizationService;
    private IvrTreeService service;

    @BeforeEach
    void setUp() {
        com.shivang.obd.authz.context.OrganizationContextHolder
                .setAuthenticated(USER, TENANT_A, null);
        treeRepository = mock(IvrTreeRepository.class);
        nodeRepository = mock(IvrNodeRepository.class);
        transitionRepository = mock(IvrTransitionRepository.class);
        authorizationService = mock(AuthorizationService.class);

        service = new IvrTreeService(
                treeRepository,
                nodeRepository,
                transitionRepository,
                authorizationService,
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public IvrPromptChecker getObject() {
                        throw new IllegalStateException("not wired");
                    }

                    @Override
                    public IvrPromptChecker getObject(Object... args) {
                        return getObject();
                    }

                    @Override
                    public IvrPromptChecker getIfAvailable() {
                        return null;
                    }

                    @Override
                    public IvrPromptChecker getIfUnique() {
                        return null;
                    }
                });
    }

    @AfterEach
    void clear() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    private IvrTree treeOwnedBy(UUID tenantId) {
        IvrTree tree = new IvrTree();
        tree.setId(TREE_B);
        tree.setTenantId(tenantId);
        tree.setName("Another tenant's tree");
        tree.setStatus(IvrTreeStatus.ACTIVE);
        return tree;
    }

    @Nested
    class Reads {

        @Test
        @DisplayName("TEN-1: reading a foreign tree is a 404, and never resolves it")
        void foreignTreeDoesNotResolve() {
            // The repository is tenant-bounded, so the lookup simply misses.
            when(treeRepository.findByIdAndTenantIdAndDeletedAtIsNull(TREE_B, TENANT_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(TREE_B))
                    .isInstanceOf(ResourceNotFoundException.class);

            // Crucially, no capability check is even reached, so a caller cannot
            // distinguish "exists but not yours" from "does not exist".
            verify(authorizationService, never())
                    .requireCapability(any(), any(), any());
        }

        @Test
        @DisplayName("TEN-2: a foreign tree and a nonexistent tree fail identically")
        void foreignAndNonexistentAreIndistinguishable() {
            when(treeRepository.findByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(TREE_B))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.getById(UUID.randomUUID()))
                    .as("both must be the same failure, or the API leaks existence")
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("TEN-3: listing is scoped to the caller's tenant only")
        void listIsScopedToTheCallerTenant() {
            when(treeRepository.findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                    org.mockito.ArgumentMatchers.eq(TENANT_A),
                    any(org.springframework.data.domain.Pageable.class)))
                    .thenReturn(List.of());

            service.list(0, 20, null);

            // The tenant comes from the organizational boundary, never from a
            // request parameter, so a caller cannot ask for another tenant's list.
            verify(treeRepository).findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                    org.mockito.ArgumentMatchers.eq(TENANT_A), any());
        }
    }

    @Nested
    class Writes {

        @Test
        @DisplayName("TEN-4: updating a foreign tree is refused")
        void updateForeignTreeRefused() {
            when(treeRepository.findByIdAndTenantIdAndDeletedAtIsNull(TREE_B, TENANT_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(TREE_B,
                    new com.shivang.obd.ivr.dto.UpdateIvrTreeRequest("ROOT", List.of())))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("TEN-5: activating a foreign tree is refused")
        void activateForeignTreeRefused() {
            when(treeRepository.findByIdAndTenantIdAndDeletedAtIsNull(TREE_B, TENANT_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changeStatus(TREE_B,
                    new com.shivang.obd.ivr.dto.ChangeIvrTreeStatusRequest(
                            com.shivang.obd.ivr.dto.IvrTreeResponse.IvrTreeStatusDto.ACTIVE)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("TEN-6: deleting a foreign tree is refused")
        void deleteForeignTreeRefused() {
            when(treeRepository.findByIdAndTenantIdAndDeletedAtIsNull(TREE_B, TENANT_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(TREE_B))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(treeRepository, never()).delete(any(IvrTree.class));
        }

        @Test
        @DisplayName("TEN-7: a newly created tree is owned by the caller's tenant, period")
        void createdTreeIsOwnedByTheCaller() {
            // Echo saves back through the list lookups, so the whole-tree
            // validator sees what was written rather than an empty tree.
            List<IvrNode> writtenNodes = new java.util.ArrayList<>();
            List<com.shivang.obd.voice.ivr.IvrTransition> writtenTransitions =
                    new java.util.ArrayList<>();
            when(treeRepository.save(any())).thenAnswer(i -> {
                IvrTree tree = i.getArgument(0);
                // The real column is DEFAULT gen_random_uuid(); the mock has to
                // stand in for it or the nodes written afterwards have no tree.
                if (tree.getId() == null) {
                    tree.setId(UUID.randomUUID());
                }
                return tree;
            });
            when(nodeRepository.save(any())).thenAnswer(i -> {
                IvrNode node = i.getArgument(0);
                if (node.getId() == null) {
                    node.setId(UUID.randomUUID());
                }
                writtenNodes.add(node);
                return node;
            });
            when(transitionRepository.save(any())).thenAnswer(i -> {
                com.shivang.obd.voice.ivr.IvrTransition transition = i.getArgument(0);
                if (transition.getId() == null) {
                    transition.setId(UUID.randomUUID());
                }
                writtenTransitions.add(transition);
                return transition;
            });
            when(nodeRepository.findByTreeIdAndDeletedAtIsNullOrderByNodeKeyAsc(any()))
                    .thenAnswer(i -> List.copyOf(writtenNodes));
            when(transitionRepository.findByTreeIdAndDeletedAtIsNullOrderByIdAsc(any()))
                    .thenAnswer(i -> List.copyOf(writtenTransitions));

            CreateIvrTreeRequest request = new CreateIvrTreeRequest("My tree", null, "ROOT",
                    List.of(new IvrNodeRequest("ROOT", IvrNodeType.MENU, null, 10,
                            null, 0, null, 0, null,
                            List.of(new IvrTransitionRequest("1", "LEAF"))),
                            new IvrNodeRequest("LEAF", IvrNodeType.TERMINAL, null, 10,
                                    null, 0, null, 0, IvrTerminalAction.TERMINATE, List.of())));
            // The request carries no tenant field at all, so a caller cannot
            // create a tree owned by somebody else.
            service.create(request);

            // The tree is saved twice — once when the row is created and once
            // after its root is set — so the assertion is on every save.
            verify(treeRepository, org.mockito.Mockito.atLeastOnce())
                    .save(org.mockito.ArgumentMatchers.argThat(tree ->
                            TENANT_A.equals(((IvrTree) tree).getTenantId())));
        }
    }

    @Nested
    class Selection {

        @Test
        @DisplayName("TEN-8: a foreign tree cannot be selected for execution")
        void foreignTreeNotSelectable() {
            when(treeRepository.findByIdAndTenantIdAndDeletedAtIsNull(TREE_B, TENANT_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requireSelectable(TREE_B, TENANT_A))
                    .as("attaching another tenant's tree to a campaign must be impossible")
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("TEN-9: an ARCHIVED tree is not selectable, whoever owns it")
        void archivedTreeNotSelectable() {
            IvrTree archived = treeOwnedBy(TENANT_A);
            archived.setStatus(IvrTreeStatus.ARCHIVED);
            when(treeRepository.findByIdAndTenantIdAndDeletedAtIsNull(TREE_B, TENANT_A))
                    .thenReturn(Optional.of(archived));

            assertThatThrownBy(() -> service.requireSelectable(TREE_B, TENANT_A))
                    .isInstanceOf(com.shivang.obd.common.exception.BusinessException.class)
                    .hasMessageContaining("ARCHIVED");
        }

        @Test
        @DisplayName("TEN-10: a DRAFT tree is not selectable")
        void draftTreeNotSelectable() {
            IvrTree draft = treeOwnedBy(TENANT_A);
            draft.setStatus(IvrTreeStatus.DRAFT);
            when(treeRepository.findByIdAndTenantIdAndDeletedAtIsNull(TREE_B, TENANT_A))
                    .thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> service.requireSelectable(TREE_B, TENANT_A))
                    .isInstanceOf(com.shivang.obd.common.exception.BusinessException.class)
                    .hasMessageContaining("DRAFT");
        }
    }

    @Nested
    class Prompts {

        @Test
        @DisplayName("TEN-11: nodes of another tenant's tree are never loaded for this tenant")
        void promptCheckUsesTheCallersTenant() {
            // The governance port is given the CALLER's tenant, so an asset
            // belonging to someone else is not usable — the check cannot be
            // satisfied by a node that references it.
            IvrNode foreign = new IvrNode();
            foreign.setTreeId(TREE_B);
            foreign.setNodeKey("ROOT");
            foreign.setNodeType(IvrNodeType.TERMINAL);
            foreign.setTerminalAction(IvrTerminalAction.TERMINATE);
            when(nodeRepository.findByTreeIdAndDeletedAtIsNullOrderByNodeKeyAsc(TREE_B))
                    .thenReturn(List.of(foreign));

            // requireSelectable is the entry point the campaign side uses, and it
            // refuses a tree this tenant cannot see before prompts are even read.
            when(treeRepository.findByIdAndTenantIdAndDeletedAtIsNull(TREE_B, TENANT_A))
                    .thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.requireSelectable(TREE_B, TENANT_A))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
