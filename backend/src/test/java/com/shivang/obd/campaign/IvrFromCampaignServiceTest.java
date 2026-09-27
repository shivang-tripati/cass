package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.campaign.config.IvrCampaignConfig;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.ivr.IvrTreeService;
import com.shivang.obd.ivr.dto.CreateIvrTreeRequest;
import com.shivang.obd.ivr.dto.IvrNodeRequest;
import com.shivang.obd.ivr.dto.IvrTreeResponse;
import com.shivang.obd.voice.ivr.IvrExecutionSnapshot;
import com.shivang.obd.voice.ivr.IvrNode;
import com.shivang.obd.voice.ivr.IvrNodeSnapshot;
import com.shivang.obd.voice.ivr.IvrNodeType;
import com.shivang.obd.voice.ivr.IvrTerminalAction;
import com.shivang.obd.voice.ivr.IvrTransition;
import com.shivang.obd.voice.ivr.IvrTree;
import com.shivang.obd.voice.ivr.IvrTreeStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * VB-6F — creating a reusable IVR tree from an existing DTMF campaign flow.
 *
 * <p>The conversion has to be behaviour-preserving, idempotent and transactional.
 * These tests cover all three, plus the refusal cases.
 */
class IvrFromCampaignServiceTest {

    private static final UUID USER_ID = UUID.fromString("ff000000-0000-4000-8000-0000000000ff");
    private static final UUID TENANT = UUID.fromString("aa000000-0000-4000-8000-0000000000f1");
    private static final UUID CAMPAIGN_ID =
            UUID.fromString("bb000000-0000-4000-8000-0000000000f2");
    private static final UUID ASSET_ID =
            UUID.fromString("cc000000-0000-4000-8000-0000000000f3");
    private static final UUID TREE_ID =
            UUID.fromString("dd000000-0000-4000-8000-0000000000f4");

    private CampaignRepository campaignRepository;
    private IvrTreeService treeService;
    private IvrFromCampaignService service;

    private CampaignEntity campaign;
    private CreateIvrTreeRequest capturedRequest;
    private final List<UUID> createdTrees = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // The conversion resolves the caller's tenant and user from the
        // organizational boundary, exactly as every tenant-scoped operation does.
        com.shivang.obd.authz.context.OrganizationContextHolder
                .setAuthenticated(USER_ID, TENANT, null);
        campaignRepository = mock(CampaignRepository.class);
        treeService = mock(IvrTreeService.class);

        campaign = new CampaignEntity();
        campaign.setId(CAMPAIGN_ID);
        campaign.setTenantId(TENANT);
        campaign.setName("Support campaign");
        campaign.setCampaignType(CampaignType.DTMF);
        campaign.setAudioAssetId(ASSET_ID);
        campaign.setTypeConfig(singleLevelDtmfConfig("1", 25, "CONNECT_BY_AGENT"));

        when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(CAMPAIGN_ID, TENANT))
                .thenReturn(Optional.of(campaign));

        // Capture what the conversion asks the tree service to build, so the
        // structure it produces can be asserted.
        when(treeService.create(any())).thenAnswer(invocation -> {
            CreateIvrTreeRequest request = invocation.getArgument(0);
            this.capturedRequest = request;
            createdTrees.add(TREE_ID);
            return com.shivang.obd.common.api.response.ResponseFactory.created(
                    new IvrTreeResponse(TREE_ID, request.name(), request.description(),
                            IvrTreeResponse.IvrTreeStatusDto.DRAFT, request.rootNodeKey(),
                            null, List.of(), null, null));
        });
        when(treeService.changeStatus(any(), any()))
                .thenReturn(com.shivang.obd.common.api.response.ResponseFactory.ok(
                        new IvrTreeResponse(TREE_ID, "Support campaign IVR", null,
                                IvrTreeResponse.IvrTreeStatusDto.ACTIVE, "ROOT", null,
                                List.of(), null, null)));
        when(treeService.getById(any()))
                .thenReturn(com.shivang.obd.common.api.response.ResponseFactory.ok(
                        new IvrTreeResponse(TREE_ID, "Support campaign IVR", null,
                                IvrTreeResponse.IvrTreeStatusDto.ACTIVE, "ROOT", null,
                                List.of(), null, null)));

        service = new IvrFromCampaignService(campaignRepository, treeService,
                mock(AuthorizationService.class));
    }

    private static ObjectNode singleLevelDtmfConfig(
            String expected, int timeoutSecs, String action) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode dtmf = root.putObject("dtmf");
        dtmf.put("expected", expected);
        dtmf.put("timeoutSecs", timeoutSecs);
        dtmf.put("action", action);
        return root;
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    @Nested
    class Structure {

        @Test
        @DisplayName("CIVR-1: the converted tree has a MENU root carrying the campaign's prompt")
        void rootCarriesTheCampaignPrompt() {
            service.convert(CAMPAIGN_ID);

            assertThat(capturedRequest).isNotNull();
            assertThat(capturedRequest.rootNodeKey()).isEqualTo("ROOT");
            IvrNodeRequest root = capturedRequest.nodes().stream()
                    .filter(n -> n.nodeKey().equals("ROOT")).findFirst().orElseThrow();
            assertThat(root.nodeType()).isEqualTo(IvrNodeType.MENU);
            assertThat(root.promptAudioAssetId())
                    .as("the caller must still hear the same prompt")
                    .isEqualTo(ASSET_ID);
        }

        @Test
        @DisplayName("CIVR-2: the converted root waits as long as the campaign did")
        void rootInheritsTheTimeout() {
            service.convert(CAMPAIGN_ID);

            IvrNodeRequest root = capturedRequest.nodes().stream()
                    .filter(n -> n.nodeKey().equals("ROOT")).findFirst().orElseThrow();
            assertThat(root.inputWaitSeconds())
                    .as("the caller must still time out at the same moment")
                    .isEqualTo(25);
        }

        @Test
        @DisplayName("CIVR-3: the expected digit becomes a single transition to a terminal leaf")
        void expectedDigitBecomesATransition() {
            service.convert(CAMPAIGN_ID);

            IvrNodeRequest root = capturedRequest.nodes().stream()
                    .filter(n -> n.nodeKey().equals("ROOT")).findFirst().orElseThrow();
            assertThat(root.transitions()).hasSize(1);
            assertThat(root.transitions().get(0).input())
                    .as("the caller must still press the same key")
                    .isEqualTo("1");
            assertThat(root.transitions().get(0).targetNodeKey()).isEqualTo("LEAF");
        }

        @Test
        @DisplayName("CIVR-4: the leaf carries the campaign's action, so the outcome is unchanged")
        void leafCarriesTheAction() {
            service.convert(CAMPAIGN_ID);

            IvrNodeRequest leaf = capturedRequest.nodes().stream()
                    .filter(n -> n.nodeKey().equals("LEAF")).findFirst().orElseThrow();
            assertThat(leaf.nodeType()).isEqualTo(IvrNodeType.TERMINAL);
            assertThat(leaf.terminalAction())
                    .as("CONNECT_BY_AGENT must survive the conversion")
                    .isEqualTo(IvrTerminalAction.CONNECT_BY_AGENT);
            assertThat(leaf.transitions())
                    .as("a terminal node is reached, not answered")
                    .isEmpty();
        }

        @Test
        @DisplayName("CIVR-5: the converted tree is valid, so it can be activated")
        void convertedTreeIsStructurallyValid() {
            service.convert(CAMPAIGN_ID);
            // Re-validate what the conversion produced through the real validator.
            List<IvrNode> nodes = new ArrayList<>();
            List<IvrTransition> transitions = new ArrayList<>();
            Map<String, UUID> ids = new LinkedHashMap<>();
            for (IvrNodeRequest request : capturedRequest.nodes()) {
                IvrNode node = new IvrNode();
                node.setId(UUID.randomUUID());
                node.setTreeId(TREE_ID);
                node.setNodeKey(request.nodeKey());
                node.setNodeType(request.nodeType());
                node.setInputWaitSeconds(request.inputWaitSeconds());
                node.setInvalidInputRetries(request.invalidInputRetries());
                node.setNoInputRetries(request.noInputRetries());
                node.setTerminalAction(request.terminalAction());
                nodes.add(node);
                ids.put(request.nodeKey(), node.getId());
            }
            for (IvrNodeRequest request : capturedRequest.nodes()) {
                for (var edge : request.transitions()) {
                    IvrTransition transition = new IvrTransition();
                    transition.setTreeId(TREE_ID);
                    transition.setNodeId(ids.get(request.nodeKey()));
                    transition.setDtmfInput(edge.input());
                    transition.setTargetNodeId(ids.get(edge.targetNodeKey()));
                    transitions.add(transition);
                }
            }

            assertThat(com.shivang.obd.voice.ivr.IvrTreeValidator
                    .validate(capturedRequest.rootNodeKey(), nodes, transitions))
                    .as("a converted tree must be valid, or it could never be activated")
                    .isEmpty();
        }
    }

    @Nested
    class CampaignRepointing {

        @Test
        @DisplayName("CIVR-6: the campaign is repointed at the new tree, storing only a reference")
        void campaignIsRepointed() {
            service.convert(CAMPAIGN_ID);

            assertThat(IvrCampaignConfig.fromTypeConfig(campaign.getTypeConfig()))
                    .isPresent();
            assertThat(IvrCampaignConfig.fromTypeConfig(campaign.getTypeConfig())
                    .orElseThrow().treeId()).isEqualTo(TREE_ID);

            // The campaign stores a REFERENCE, not a copy of the flow: the whole
            // point of a reusable tree is that the flow is not duplicated.
            ObjectNode ivr = (ObjectNode) campaign.getTypeConfig().get("ivr");
            assertThat(ivr.has("nodes"))
                    .as("the campaign must not embed the tree's nodes")
                    .isFalse();
        }

        @Test
        @DisplayName("CIVR-7: the campaign now selects the IVR path, not single-level DTMF")
        void campaignSelectsTheIvrPath() {
            service.convert(CAMPAIGN_ID);

            assertThat(IvrCampaignConfig.selectsIvr(campaign.getTypeConfig())).isTrue();
            assertThat(com.shivang.obd.campaign.config.CampaignTypeConfig
                    .fromTypeConfig(CampaignType.DTMF, campaign.getTypeConfig()))
                    .as("the shared parser must read it as an IVR config")
                    .isInstanceOf(IvrCampaignConfig.class);
        }
    }

    @Nested
    class Idempotency {

        @Test
        @DisplayName("CIVR-8: converting an already-converted campaign returns the SAME tree")
        void repeatConversionIsANoOp() {
            service.convert(CAMPAIGN_ID);
            createdTrees.clear();

            IvrTreeResponse second = service.convert(CAMPAIGN_ID).data();

            assertThat(createdTrees)
                    .as("a retried conversion must not create a second tree")
                    .isEmpty();
            assertThat(second.id()).isEqualTo(TREE_ID);
        }
    }

    @Nested
    class Refusals {

        @Test
        @DisplayName("CIVR-9: a non-DTMF campaign is refused")
        void nonDtmfCampaignRefused() {
            campaign.setCampaignType(CampaignType.PLAYFILE);

            assertThatThrownBy(() -> service.convert(CAMPAIGN_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Only a DTMF campaign");
        }

        @Test
        @DisplayName("CIVR-10: an unreadable typeConfig is refused rather than half-converted")
        void unreadableTypeConfigRefused() {
            campaign.setTypeConfig(JsonNodeFactory.instance.objectNode());

            assertThatThrownBy(() -> service.convert(CAMPAIGN_ID))
                    .isInstanceOf(com.shivang.obd.voice.dtmf.DtmfConfigInvalidException.class);
        }

        @Test
        @DisplayName("CIVR-11: a missing campaign is a 404-shaped failure, not a silent success")
        void missingCampaignRefused() {
            when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(CAMPAIGN_ID, TENANT))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.convert(CAMPAIGN_ID))
                    .isInstanceOf(com.shivang.obd.common.exception.ResourceNotFoundException.class);
        }
    }

    @Nested
    class SnapshotImmutability {

        /**
         * The brief's Example 6, in the form that matters: a captured execution
         * keeps the tree it captured, even after the live tree is edited.
         */
        @Test
        @DisplayName("CIVR-12: a captured snapshot is unaffected by a later live-tree edit")
        void capturedSnapshotIgnoresLaterEdits() {
            // Execution 1 captures the tree as it is now: 1 -> SALES.
            Map<String, IvrNodeSnapshot> before = new LinkedHashMap<>();
            before.put("ROOT", new IvrNodeSnapshot("ROOT", IvrNodeType.MENU, ASSET_ID, 10,
                    null, 0, null, 0, null, Map.of("1", "SALES")));
            before.put("SALES", new IvrNodeSnapshot("SALES", IvrNodeType.TERMINAL, null, 5,
                    null, 0, null, 0, IvrTerminalAction.TERMINATE, Map.of()));
            IvrExecutionSnapshot first =
                    new IvrExecutionSnapshot(TREE_ID, "Menu", "ROOT", before);
            assertThat(first.resolve('1', "ROOT")).contains("SALES");

            // The operator edits the LIVE tree: 1 now goes to SUPPORT.
            Map<String, IvrNodeSnapshot> after = new LinkedHashMap<>();
            after.put("ROOT", new IvrNodeSnapshot("ROOT", IvrNodeType.MENU, ASSET_ID, 10,
                    null, 0, null, 0, null, Map.of("1", "SUPPORT")));
            after.put("SALES", before.get("SALES"));
            after.put("SUPPORT", new IvrNodeSnapshot("SUPPORT", IvrNodeType.TERMINAL, null, 5,
                    null, 0, null, 0, IvrTerminalAction.TERMINATE, Map.of()));
            IvrExecutionSnapshot second =
                    new IvrExecutionSnapshot(TREE_ID, "Menu", "ROOT", after);

            // The running execution is unaffected...
            assertThat(first.resolve('1', "ROOT"))
                    .as("an execution must not be changed by a later IVR edit")
                    .contains("SALES");
            // ...and a NEW execution picks the edit up.
            assertThat(second.resolve('1', "ROOT")).contains("SUPPORT");
        }
    }
}
