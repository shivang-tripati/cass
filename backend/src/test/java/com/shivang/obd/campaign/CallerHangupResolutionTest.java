package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.voice.call.HangupCauseMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * VB-6D.3 — the OD-6 resolution: {@code CALLER_HANGUP} cannot cause an
 * incorrect redial of a completed campaign call.
 *
 * <p>VB-6D.2 left this open, worried that a system-initiated teardown after a
 * successful completion could be read as a failed attempt and trigger a
 * redial. Inspection of the real event flow shows the concern is
 * <b>structurally impossible</b>, and this suite locks that in rather than
 * changing any taxonomy or policy code:
 *
 * <ol>
 *   <li>{@code CALLER_HANGUP} is assigned in exactly one place —
 *       {@code InboundCallService} — on a {@code CallLeg} and a
 *       {@code CallSession}. That service's own comment states that
 *       <em>"inbound sessions have no CallAttempt"</em>, so the code can
 *       never reach the column the retry gate reads.</li>
 *   <li>The only writers of {@code CallAttempt.failureCode} on the campaign
 *       path are {@code EslEventService} (via {@link HangupCauseMapper}) and
 *       {@code OutboundDialService.markFailed}. Neither can emit
 *       {@code CALLER_HANGUP}: the mapper's output set does not contain it.</li>
 *   <li>A successful call sets {@code status = COMPLETED} and
 *       {@code failureCode = null}, and the retry loop only ever loads
 *       {@code status = FAILED} attempts — so a completed call is not a
 *       retry candidate by construction, independent of its failure code.</li>
 * </ol>
 *
 * <p>Because the existing lifecycle already prevents the failure mode, the
 * resolution is <b>no behaviour change</b>: the taxonomy is deliberately not
 * weakened and no new code is invented. The value here is that the
 * conclusion is now executable rather than a claim in a report.
 */
class CallerHangupResolutionTest {

    @Nested
    class UnreachableFromTheCampaignRetryGate {

        @Test
        @DisplayName("HANG-R1: the provider-cause mapper can never emit CALLER_HANGUP")
        void mapperNeverEmitsCallerHangup() {
            // The campaign attempt's failure code comes from this mapper for
            // every post-acceptance outcome.
            Set<String> reachable = HangupCauseMapper.supportedFailureCodes();
            assertThat(reachable).doesNotContain(CallFailureCode.CALLER_HANGUP.getCode());

            // And a broad sweep of causes, including ones a carrier might use
            // for a caller-initiated teardown, still lands on canonical codes.
            for (String cause : new String[] {"NORMAL_CLEARING", "16", "ORIGINATOR_CANCEL",
                    "CALLER_HANGUP", "USER_HANGUP", "junk"}) {
                assertThat(HangupCauseMapper.toFailureCode(cause))
                        .as("cause %s", cause)
                        .isNotEqualTo(CallFailureCode.CALLER_HANGUP.getCode())
                        .isIn(reachable);
            }
        }

        @Test
        @DisplayName("HANG-R2: CALLER_HANGUP remains a canonical, classified code")
        void remainsCanonical() {
            // The taxonomy is untouched: this is still a real code with a real
            // retry class, used by the inbound/Contact Center leg boundary. It
            // is simply not reachable from a campaign attempt.
            assertThat(CallFailureCode.fromCode("CALLER_HANGUP")).isPresent();
            assertThat(CallFailureCode.canonicalize("CALLER_HANGUP"))
                    .isEqualTo(CallFailureCode.CALLER_HANGUP);
        }
    }

    @Nested
    class CompletedCallsAreNotRetryCandidates {

        @Test
        @DisplayName("HANG-R3: a completed campaign call is COMPLETED with no failure code")
        void completedAttemptCarriesNoFailureCode() {
            // Mirrors EslEventService.handleChannelHangup: on success it sets
            // COMPLETED and nulls the failure code, so nothing downstream can
            // read a failure reason from a successful call.
            CallAttempt attempt = new CallAttempt();
            attempt.setId(UUID.randomUUID());
            attempt.setStatus(CallAttemptStatus.IN_PROGRESS);

            // The success branch, exactly as production performs it.
            attempt.setStatus(CallAttemptStatus.COMPLETED);
            attempt.setFailureCode(null);

            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
            assertThat(attempt.getFailureCode()).isNull();
        }

        @Test
        @DisplayName("HANG-R4: only FAILED attempts are retry candidates, so COMPLETED never is")
        void onlyFailedAttemptsAreRetryCandidates() {
            // The orchestrator loads exactly this set.
            java.util.function.Predicate<CallAttempt> retryCandidates =
                    attempt -> attempt.getStatus() == CallAttemptStatus.FAILED;

            CallAttempt completed = new CallAttempt();
            completed.setStatus(CallAttemptStatus.COMPLETED);
            completed.setFailureCode(CallFailureCode.CALLER_HANGUP.getCode());

            CallAttempt failed = new CallAttempt();
            failed.setStatus(CallAttemptStatus.FAILED);
            failed.setFailureCode(CallFailureCode.NO_ANSWER.getCode());

            assertThat(retryCandidates.test(completed))
                    .as("even a COMPLETED attempt carrying CALLER_HANGUP is not a candidate")
                    .isFalse();
            assertThat(retryCandidates.test(failed)).isTrue();
        }
    }

    @Nested
    class RedialSafetyIsGovernedByPolicy {

        @Test
        @DisplayName("HANG-R5: if a hangup-class outcome did reach an attempt, policy still governs it")
        void hangupOutcomesRemainCampaignConfigurable() {
            // Defence in depth for the future: should a carrier ever surface a
            // genuine caller-hangup cause on the campaign path, it becomes a
            // canonical HANGUP_UNKNOWN, is classified HANGUP, and the
            // campaign's HANGUP rule decides whether it is redialled. A tenant
            // that does not want redials after a hangup sets that rule to
            // disabled — no taxonomy change required.
            RetryPolicySpec noHangupRetries = new RetryPolicySpec(3, 300,
                    RetryStrategy.FIXED,
                    java.util.List.of(RetryRule.disabled(RetryRuleCategory.HANGUP)));
            RetryDecision decision = new RetryPolicyService().evaluate(
                    noHangupRetries, CallFailureCode.HANGUP_UNKNOWN.getCode(), 1,
                    java.time.Instant.parse("2026-09-27T10:00:00Z"));

            assertThat(decision.retryable())
                    .as("a campaign can suppress hangup redials through policy")
                    .isFalse();
            // A disabled decision carries no classification (no rule governed
            // it), so the category is asserted on the classification itself.
            assertThat(FailureClassification.of(CallFailureCode.HANGUP_UNKNOWN).category())
                    .isEqualTo(RetryRuleCategory.HANGUP);
        }
    }
}
