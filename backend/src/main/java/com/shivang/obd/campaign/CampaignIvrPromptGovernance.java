package com.shivang.obd.campaign;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.ivr.IvrPromptChecker;
import com.shivang.obd.voice.ivr.IvrNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Campaign-side implementation of the IVR prompt-governance port (VB-6F).
 *
 * <p>All the real work is delegated to
 * {@link CampaignResourceValidationService}, the single authority campaigns
 * already use. This class decides only <em>when</em> to ask, and formats the
 * answer; it holds no ownership or approval rule of its own, which is what keeps
 * a prompt inside an IVR node exactly as governed as a campaign's audio asset.
 *
 * <p>It lives in {@code campaign} rather than {@code ivr} because the validator it
 * delegates to lives here, and {@code campaign} already depends on {@code ivr}
 * for snapshot capture and the IVR runtime. Putting it the other way round would
 * close a module cycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignIvrPromptGovernance implements IvrPromptChecker {

    private final CampaignResourceValidationService resourceValidator;

    @Override
    public void requireUsablePrompts(List<IvrNode> nodes, UUID tenantId) {
        List<String> problems = problems(nodes, tenantId);
        if (!problems.isEmpty()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                    "IVR tree has prompts that are not usable for this tenant: "
                            + String.join("; ", problems));
        }
    }

    /**
     * Whether one prompt may be played to this tenant right now.
     * <p>
     * The runtime calls this immediately before each playback, so a resource
     * revoked mid-call is refused instead of dialled. Never throws: an unusable
     * prompt is a permanent configuration fault for that node, which the caller
     * records through the existing permanent-failure path.
     *
     * @return null when usable, otherwise the reason
     */
    public String unusableReason(UUID audioAssetId, UUID tenantId) {
        if (audioAssetId == null) {
            return null; // no prompt configured; a silent node is legal
        }
        var result = resourceValidator.validateAudio(audioAssetId, tenantId);
        if (result.usable()) {
            return null;
        }
        return switch (result.code()) {
            case AUDIO_NOT_AVAILABLE -> "prompt audio asset is not available for this tenant";
            case AUDIO_NOT_APPROVED -> "prompt audio asset is not approved";
            default -> "prompt audio asset has no usable storage reference";
        };
    }

    /** Every prompt problem in the tree, de-duplicated and in a stable order. */
    private List<String> problems(List<IvrNode> nodes, UUID tenantId) {
        Set<String> messages = new LinkedHashSet<>();
        for (IvrNode node : nodes) {
            collect(node, node.getPromptAudioAssetId(), "prompt", messages, tenantId);
            collect(node, node.getInvalidPromptAudioAssetId(),
                    "invalid-input prompt", messages, tenantId);
            collect(node, node.getNoInputPromptAudioAssetId(),
                    "no-input prompt", messages, tenantId);
        }
        return new ArrayList<>(messages);
    }

    private void collect(IvrNode node, UUID assetId, String role, Set<String> messages,
                         UUID tenantId) {
        String reason = unusableReason(assetId, tenantId);
        if (reason != null) {
            messages.add("node '" + node.getNodeKey() + "' " + role + ": " + reason);
        }
    }
}
