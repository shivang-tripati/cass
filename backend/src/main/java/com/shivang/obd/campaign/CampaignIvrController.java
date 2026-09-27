package com.shivang.obd.campaign;

import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.ivr.IvrTreeService;
import com.shivang.obd.ivr.dto.IvrTreeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Campaign-side IVR integration (VB-6F).
 *
 * <p>Kept separate from the campaign CRUD controller and from the IVR tree
 * controller because these are operations on a <em>campaign</em> that happen to
 * concern IVR, not operations on a tree. Keeping them apart also keeps the module
 * dependency one-directional: the IVR resource module knows nothing about
 * campaigns, and the campaign module calls into it.
 */
@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
@Tag(name = "Campaigns", description = "Campaign lifecycle, executions and campaign-scoped IVR "
    + "integration.")
public class CampaignIvrController {

    private final IvrFromCampaignService ivrFromCampaignService;

    @Operation(
        summary = "Create a reusable IVR tree from a campaign's DTMF flow",
        description = "Converts an existing single-level DTMF campaign configuration into a "
            + "reusable IVR tree and repoints the campaign at it. Behaviour is preserved exactly: "
            + "the caller still hears the same prompt, must still press the same digit, still gets "
            + "the same terminal action, and still times out after the same number of seconds. What "
            + "changes is that the flow becomes editable and shareable, so other campaigns can "
            + "reference it and one edit updates all of them. The campaign is not modified until "
            + "the tree has been created and activated, and the whole conversion is one "
            + "transaction. Idempotent: if the campaign already references a tree, that tree is "
            + "returned unchanged rather than a second one being created. Only a DTMF campaign has "
            + "a convertible flow.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
        description = "Tree created from the campaign's flow, activated, and the campaign now "
            + "references it")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
        description = "The campaign is not a DTMF campaign, or its typeConfig is not a readable "
            + "single-level DTMF configuration")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
        description = "Not authenticated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
        description = "Missing IVR_MANAGE capability for the owning tenant")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
        description = "Not found, or outside the caller's boundary")
    @PostMapping("/{id}/ivr-tree")
    public ResponseEntity<ApiResponse<IvrTreeResponse>> createIvrTreeFromCampaign(
        @PathVariable UUID id
    ) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(ivrFromCampaignService.convert(id));
    }
}
