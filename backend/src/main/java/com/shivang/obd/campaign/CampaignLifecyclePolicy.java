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

    /**
     * VB-8B: the lifecycle states in which a campaign is allowed to execute —
     * the same set the readiness gate has always applied. Named here so the
     * rule has exactly one authority, and so the editability/executability
     * invariant below can be asserted rather than assumed.
     */
    private static final java.util.Set<CampaignStatus> EXECUTABLE_STATUSES =
            java.util.Set.of(CampaignStatus.SCHEDULED, CampaignStatus.RUNNING);

    /**
     * VB-8B: whether the campaign is in a state the execution engine will act
     * on. Delegates to {@link #isEditable} semantics' counterpart without
     * needing a loaded entity.
     *
     * <p>This is the readiness gate's own rule
     * ({@code CampaignReadinessService.checkLifecycleState}), extracted so the
     * gate and this predicate cannot drift apart.
     */
    public static boolean isExecutable(CampaignStatus status) {
        return status != null && EXECUTABLE_STATUSES.contains(status);
    }

    /**
     * VB-8B: the invariant that makes the frozen execution snapshot safe at the
     * readiness gate.
     *
     * <p>Campaign configuration can only be mutated in a state that is never
     * executable, so no post-freeze campaign edit can be observed as an
     * execution-eligibility decision: a campaign that was edited is necessarily
     * not executable, and the gate defers rather than acting on it. That is why
     * the scheduler may evaluate readiness against the live campaign without
     * the mutable state leaking into what an execution actually runs.
     *
     * <p><b>This is a real, load-bearing invariant, not decoration.</b> If a
     * future change ever lets a campaign be edited while it is executable, the
     * scheduler's readiness gate would begin deciding execution eligibility from
     * mutable configuration, and an edit could fail or unblock an execution whose
     * snapshot was frozen as runnable. {@code CampaignLifecycleInvariantsTest}
     * exists to make that regression loud.
     *
     * @return true when no campaign status is both editable and executable
     */
    public static boolean editableAndExecutableAreDisjoint() {
        for (CampaignStatus status : CampaignStatus.values()) {
            if (EDITABLE_STATUSES.contains(status) && isExecutable(status)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the campaign's configuration may currently be mutated. */
    public boolean isEditable(CampaignEntity campaign) {
        return campaign != null
                && EDITABLE_STATUSES.contains(campaign.getStatus());
    }

    /**
     * VB-8H: the single canonical reason describing why a campaign cannot
     * execute right now.
     *
     * <p>Extracted so the readiness gate and the execution response cannot
     * drift into two different explanations of the same condition. B10 was
     * exactly that drift: an execution could sit {@code REQUESTED} with nothing
     * in the API saying why.
     */
    public static com.shivang.obd.campaign.dto.CampaignReadinessReason notExecutableReason(
            CampaignStatus status) {
        return new com.shivang.obd.campaign.dto.CampaignReadinessReason(
                "CAMPAIGN_NOT_EXECUTABLE_STATE",
                "Campaign is not in an executable state: " + (status == null ? "unknown" : status.name())
                        + ". Only SCHEDULED or RUNNING campaigns can execute.");
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
