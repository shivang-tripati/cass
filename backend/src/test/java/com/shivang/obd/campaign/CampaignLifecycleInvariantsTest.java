package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.common.exception.ConflictException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * VB-8B — the lifecycle invariant that makes the frozen execution snapshot safe
 * at the scheduler's readiness gate.
 *
 * <p>VB-8A (finding F-03) established that {@code doStartExecution} evaluates
 * readiness against the <em>live</em> campaign while everything the execution
 * actually runs comes from the frozen snapshot. That is only safe because of one
 * property:
 *
 * <pre>
 *   EDITABLE_STATUSES  ∩  EXECUTABLE_STATUSES  =  ∅
 * </pre>
 *
 * <p>If a campaign's configuration can only be mutated in a state that is never
 * executable, then no post-freeze edit can be observed as an
 * execution-eligibility decision: an edited campaign is necessarily
 * non-executable, and the gate defers on it instead of acting.
 *
 * <p><b>These tests exist so that breaking the invariant is loud.</b> Nothing
 * about it is enforced by the compiler or by a database constraint, so a future
 * change that widened editability to an executable state would silently
 * reintroduce the F-03 hazard. That is the regression these guard.
 */
class CampaignLifecycleInvariantsTest {

    @Test
    @DisplayName("F03-1: no campaign status is both editable and executable")
    void editableAndExecutableAreDisjoint() {
        assertThat(CampaignLifecyclePolicy.editableAndExecutableAreDisjoint())
                .as("the readiness gate may read live campaign state only while this holds")
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(CampaignStatus.class)
    @DisplayName("F03-2: a status that permits configuration mutation is never a runnable state")
    void mutableStatusIsNeverExecutable(CampaignStatus status) {
        CampaignEntity campaign = new CampaignEntity();
        campaign.setStatus(status);

        CampaignLifecyclePolicy policy = new CampaignLifecyclePolicy();

        assertThat(policy.isEditable(campaign) && CampaignLifecyclePolicy.isExecutable(status))
                .as("%s must not be both editable and executable", status)
                .isFalse();
    }

    @Test
    @DisplayName("F03-3: the enumerable sets really do cover every status, so the test can bite")
    void everyStatusIsAccountedFor() {
        // A disjointness assertion is only meaningful if it actually inspected
        // the real enum rather than a subset. This pins that DRAFT is the one
        // editable state and SCHEDULED/RUNNING the executable ones, so if a
        // status were added or moved, the checks above become meaningful again.
        List<CampaignStatus> executable = new ArrayList<>();
        List<CampaignStatus> editable = new ArrayList<>();
        CampaignLifecyclePolicy policy = new CampaignLifecyclePolicy();
        for (CampaignStatus status : CampaignStatus.values()) {
            if (CampaignLifecyclePolicy.isExecutable(status)) {
                executable.add(status);
            }
            CampaignEntity campaign = new CampaignEntity();
            campaign.setStatus(status);
            if (policy.isEditable(campaign)) {
                editable.add(status);
            }
        }

        assertThat(executable).containsExactlyInAnyOrder(
                CampaignStatus.SCHEDULED, CampaignStatus.RUNNING);
        assertThat(editable).containsExactly(CampaignStatus.DRAFT);
    }

    @Test
    @DisplayName("F03-4: a DRAFT campaign is refused configuration mutation, as the invariant assumes")
    void draftIsEditableButExecutableOnlyAfterScheduling() {
        CampaignLifecyclePolicy policy = new CampaignLifecyclePolicy();
        CampaignEntity campaign = new CampaignEntity();

        campaign.setStatus(CampaignStatus.DRAFT);
        assertThat(policy.isEditable(campaign)).isTrue();
        assertThat(CampaignLifecyclePolicy.isExecutable(CampaignStatus.DRAFT)).isFalse();

        // The documented unlock for a scheduled campaign that needs changes:
        // SCHEDULED -> DRAFT makes it editable again, and it stops being
        // executable at the same moment. That simultaneity is the invariant.
        campaign.setStatus(CampaignStatus.SCHEDULED);
        assertThat(policy.isEditable(campaign)).isFalse();
        assertThat(CampaignLifecyclePolicy.isExecutable(CampaignStatus.SCHEDULED)).isTrue();

        campaign.setStatus(CampaignStatus.DRAFT);
        assertThat(policy.isEditable(campaign)).isTrue();
        assertThat(CampaignLifecyclePolicy.isExecutable(campaign.getStatus())).isFalse();
    }

    @Test
    @DisplayName("F03-5: refusing a non-editable edit still names the current state")
    void nonEditableCampaignIsRefusedWithItsCurrentState() {
        CampaignLifecyclePolicy policy = new CampaignLifecyclePolicy();
        CampaignEntity campaign = new CampaignEntity();
        campaign.setStatus(CampaignStatus.RUNNING);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> policy.assertEditable(campaign))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("RUNNING");
    }
}
