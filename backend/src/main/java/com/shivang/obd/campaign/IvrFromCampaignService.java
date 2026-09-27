package com.shivang.obd.campaign;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContext;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.api.response.ResponseFactory;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.ivr.IvrTreeService;
import com.shivang.obd.ivr.dto.CreateIvrTreeRequest;
import com.shivang.obd.ivr.dto.IvrNodeRequest;
import com.shivang.obd.ivr.dto.IvrTransitionRequest;
import com.shivang.obd.ivr.dto.IvrTreeResponse;
import com.shivang.obd.voice.dtmf.DtmfConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns an existing single-level DTMF campaign into a reusable IVR tree
 * (VB-6F).
 *
 * <h2>The conversion</h2>
 *
 * <pre>
 *   DTMF campaign {"dtmf":{expected, timeoutSecs, action, …}}
 *     → IvrTree            (activated immediately: it is known-valid)
 *     → root MENU node     prompt = the campaign's own audio asset
 *                          inputWaitSeconds = timeoutSecs
 *     → one transition     expected → a TERMINAL node
 *     → that TERMINAL node action = the campaign's action
 *     → campaign typeConfig rewritten to {"ivr":{treeId}}
 * </pre>
 *
 * <h2>Behaviour is preserved exactly</h2>
 *
 * <p>The caller still hears the same prompt, must still press the same digit,
 * still gets the same action on success, and still times out after the same
 * number of seconds. The only difference is that the result is now editable and
 * reusable, which is the entire point of a tree.
 *
 * <h2>Idempotent</h2>
 *
 * <p>If the campaign already references an IVR tree, the existing tree is
 * returned unchanged. Combined with a database existence check on
 * {@code (tenantId, sourceCampaignId)}, a retried or duplicated request produces
 * exactly one tree — not two.
 *
 * <h2>Transactional</h2>
 *
 * <p>The tree, its nodes, its transitions and the campaign's new typeConfig are
 * written in one transaction. A conversion that fails halfway leaves the
 * campaign on its original single-level configuration rather than pointing at a
 * half-built tree.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IvrFromCampaignService {

    private final CampaignRepository campaignRepository;
    private final IvrTreeService ivrTreeService;
    private final AuthorizationService authorizationService;

    /** Node keys are derived, not invented, so a converted tree reads sensibly. */
    private static final String ROOT_KEY = "ROOT";
    private static final String TERMINAL_KEY = "LEAF";

    /**
     * Converts a DTMF campaign's embedded flow into a reusable tree and points
     * the campaign at it.
     *
     * @return the tree, newly created or the pre-existing one on a retry
     */
    @Transactional
    public ApiResponse<IvrTreeResponse> convert(UUID campaignId) {
        UUID tenantId = OrganizationContextHolder.current()
                .map(OrganizationContext::tenantId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED,
                        "Converting a campaign to IVR requires an authenticated tenant context"));
        UUID userId = OrganizationContextHolder.currentUserId()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED,
                        "Converting a campaign to IVR requires an authenticated user"));
        authorizationService.requireCapability(userId, IvrTreeService.CAP_MANAGE,
                AccessCheck.forTenant(tenantId));

        CampaignEntity campaign = campaignRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(campaignId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found"));

        // Already converted: return the same tree rather than creating a second.
        Optional<com.shivang.obd.campaign.config.IvrCampaignConfig> existing =
                com.shivang.obd.campaign.config.IvrCampaignConfig
                        .fromTypeConfig(campaign.getTypeConfig());
        if (existing.isPresent()) {
            log.info("Campaign {} already references IVR tree {} - conversion is a no-op",
                    campaignId, existing.get().treeId());
            return ResponseFactory.ok(ivrTreeService.getById(existing.get().treeId()).data());
        }

        if (campaign.getCampaignType() != CampaignType.DTMF) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                    "Only a DTMF campaign has a flow that can be converted to an IVR tree; this "
                            + "campaign is " + campaign.getCampaignType() + ".");
        }

        DtmfConfig config = DtmfConfig.fromTypeConfig(campaign.getTypeConfig());

        CreateIvrTreeRequest request = new CreateIvrTreeRequest(
                campaign.getName() + " IVR",
                "Converted from DTMF campaign " + campaign.getId(),
                ROOT_KEY,
                List.of(
                        new IvrNodeRequest(
                                ROOT_KEY,
                                com.shivang.obd.voice.ivr.IvrNodeType.MENU,
                                campaign.getAudioAssetId(),
                                config.timeoutSecs(),
                                null, 0, null, 0, null,
                                List.of(new IvrTransitionRequest(config.expected(), TERMINAL_KEY))),
                        new IvrNodeRequest(
                                TERMINAL_KEY,
                                com.shivang.obd.voice.ivr.IvrNodeType.TERMINAL,
                                null,
                                config.timeoutSecs(),
                                null, 0, null, 0,
                                com.shivang.obd.voice.ivr.IvrTerminalAction
                                        .valueOf(config.action()),
                                List.of())));

        IvrTreeResponse created = ivrTreeService.create(request).data();
        // Activated immediately: a converted flow is known-valid by construction,
        // and leaving it DRAFT would make the campaign reference a tree it cannot
        // execute.
        IvrTreeResponse activated = ivrTreeService.changeStatus(created.id(),
                new com.shivang.obd.ivr.dto.ChangeIvrTreeStatusRequest(
                        IvrTreeResponse.IvrTreeStatusDto.ACTIVE)).data();
        ivrTreeService.recordProvenance(activated.id(), campaignId);

        campaign.setTypeConfig(
                com.shivang.obd.campaign.config.IvrCampaignConfig
                        .referencing(activated.id()).toJson());
        campaignRepository.save(campaign);

        log.info("Campaign {} converted to IVR tree {} (expected={}, timeoutSecs={}, action={})",
                campaignId, activated.id(), config.expected(), config.timeoutSecs(),
                config.action());
        return ResponseFactory.created(activated);
    }
}
