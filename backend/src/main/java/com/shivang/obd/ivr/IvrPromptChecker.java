package com.shivang.obd.ivr;

import com.shivang.obd.voice.ivr.IvrNode;
import java.util.List;
import java.util.UUID;

/**
 * Prompt-resource governance, as a port (VB-6F).
 *
 * <h2>Why this is an interface</h2>
 *
 * <p>Answering "is this prompt usable for this tenant?" requires
 * {@code CampaignResourceValidationService}, which lives in the {@code campaign}
 * module. The {@code ivr} module must not depend on {@code campaign}: the
 * campaign side depends on {@code ivr} for snapshot capture and the IVR runtime,
 * so a direct call from {@code ivr} to {@code campaign} would close a cycle.
 *
 * <p>This is the codebase's established answer to exactly this situation, used
 * twice already: {@code voice.media.DtmfCollectorTrigger} and
 * {@code PlaybackTrigger} are interfaces in {@code voice} implemented by
 * {@code campaign}, and the agent-connect boundary is injected as an
 * {@code ObjectProvider}. The same port keeps one authorization system — the
 * campaign resource authority — while keeping the module graph acyclic.
 *
 * <h2>What the implementation must do</h2>
 *
 * <p>Delegate to {@code CampaignResourceValidationService.validateAudio} and
 * nothing else. It must not introduce its own ownership or approval rule: a
 * prompt inside an IVR node must be exactly as governed as a campaign's audio
 * asset, and a second rule set is how that guarantee would quietly erode.
 */
public interface IvrPromptChecker {

    /**
     * Fails unless every prompt referenced by these nodes is usable by the
     * tenant.
     *
     * @param nodes    the tree's nodes
     * @param tenantId the owning tenant
     * @throws com.shivang.obd.common.exception.BusinessException naming every
     *         unusable prompt, so an operator can fix the tree in one pass
     */
    void requireUsablePrompts(List<IvrNode> nodes, UUID tenantId);
}
