package com.shivang.obd.campaign;

import com.shivang.obd.common.exception.ConflictException;
import org.springframework.stereotype.Service;

/**
 * Campaign editability policy (VB-6A correction): configuration mutation is
 * a lifecycle-gated operation, so the rule lives in exactly one place —
 * every mutation path calls {@link #assertEditable} instead of scattering
 * status checks.
 * <p>
 * Domain rule derived from the existing lifecycle
 * ({@link CampaignStatus} + {@code CampaignService.LEGAL_TRANSITIONS}):
 * <ul>
 *   <li>{@link CampaignStatus#DRAFT} is the only editable state — it is the
 *       configuration state; transitions SCHEDULED → DRAFT exist explicitly
 *       to unlock a scheduled campaign for editing.</li>
 *   <li>Every other state is non-editable: SCHEDULED is the validated,
 *       execution-ready configuration (the product rule "READY is not
 *       editable" — there is no separate READY status; SCHEDULED is that
 *       state), RUNNING/PAUSED/COMPLETED/FAILED have an execution history
 *       that must not be rewritten under them, and ARCHIVED is terminal.</li>
 * </ul>
 * A scheduled campaign that needs changes is explicitly unlocked first via
 * the existing SCHEDULED → DRAFT transition — no versioning, no cloning,
 * no new statuses (VB-6A correction §13).
 */
@Service
public class CampaignLifecyclePolicy {

    /** The one lifecycle state in which campaign configuration may change. */
    private static final java.util.Set<CampaignStatus> EDITABLE_STATUSES =
            java.util.Set.of(CampaignStatus.DRAFT);

    /** Whether the campaign's configuration may currently be mutated. */
    public boolean isEditable(CampaignEntity campaign) {
        return campaign != null
                && EDITABLE_STATUSES.contains(campaign.getStatus());
    }

    /**
     * Rejects configuration mutation for non-editable campaigns with a
     * deterministic 409 that names the current state.
     */
    public void assertEditable(CampaignEntity campaign) {
        if (isEditable(campaign)) {
            return;
        }
        throw new ConflictException(
                "Campaign configuration can only be modified while the campaign "
                        + "is in DRAFT state (current: "
                        + (campaign == null ? "unknown" : campaign.getStatus()) + ").");
    }
}
