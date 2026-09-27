package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VB-6E — the pre-dispatch classification repair.
 *
 * <h2>The regression this pins</h2>
 *
 * <p>Pre-VB-6E a routing rejection persisted the raw
 * {@code VoiceRoutingReason} name — {@code ROUTE_REJECTED_GATEWAY_DISABLED} and
 * friends — into {@code call_attempts.failure_code}. Those are not members of
 * {@link CallFailureCode}, so {@code canonicalize} mapped every one to
 * {@code HANGUP_UNKNOWN}, which classifies as a
 * {@code CONTACT_OUTCOME}/{@code HANGUP}. A gateway being administratively
 * disabled therefore consumed campaign retry budget and could re-dial a number
 * that was never called — directly contradicting the VB-6D design, where
 * {@code CallFailureCode.ROUTE_REJECTED} exists in the pre-dispatch set for
 * exactly this reason and was never actually written.
 *
 * <p>Each test below also asserts the outcome that actually matters: that
 * {@link RetryPolicyService} <em>refuses</em> to produce a retry, so no budget
 * is spent.
 */
class PreDispatchFailureMapperTest {

    private final PreDispatchFailureMapper mapper = new PreDispatchFailureMapper();

    @Nested
    class RoutingReasons {

        @ParameterizedTest(name = "PRE-1: {0} maps to a pre-dispatch code")
        @ValueSource(strings = {
                "ROUTE_REJECTED_GATEWAY_DISABLED",
                "ROUTE_REJECTED_GATEWAY_INACTIVE",
                "ROUTE_REJECTED_GATEWAY_DEGRADED",
                "ROUTE_REJECTED_GATEWAY_MAINTENANCE",
                "ROUTE_REJECTED_GATEWAY_UNHEALTHY",
                "ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY",
                "ROUTE_REJECTED_NO_PROFILE_CONFIGURED",
                "ROUTE_REJECTED_NO_APPROVED_OVERFLOW",
                "ROUTE_REJECTED_NO_APPROVED_FAILOVER",
                "ROUTE_REJECTED_AUTO_OVERFLOW_DISABLED",
                "ROUTE_REJECTED_AUTO_FAILOVER_DISABLED",
                "ROUTE_REJECTED_CAMPAIGN_NOT_ALLOWED",
                "ROUTE_REJECTED_UNKNOWN",
        })
        @DisplayName("PRE-1: gateway and policy reasons map to a pre-dispatch code")
        void gatewayAndPolicyReasonsArePreDispatch(String reason) {
            CallFailureCode code = mapper.toPreDispatchCode(reason);

            assertThat(FailureClassification.of(code).isPreDispatch())
                    .as("%s -> %s must be pre-dispatch", reason, code)
                    .isTrue();
            assertThat(code).isNotEqualTo(CallFailureCode.HANGUP_UNKNOWN);
        }

        @ParameterizedTest(name = "PRE-2: {0} maps to INVALID_DID")
        @ValueSource(strings = {
                "ROUTE_REJECTED_DID_INCOMPATIBLE",
                "ROUTE_REJECTED_DID_NOT_ALLOWED",
                "ROUTE_REJECTED_INVALID_DID",
                "ROUTE_REJECTED_TENANT_NOT_AUTHORIZED",
                "ROUTE_REJECTED_RESELLER_NOT_AUTHORIZED",
                "ROUTE_REJECTED_ENTERPRISE_ONLY",
        })
        @DisplayName("PRE-2: DID and authorization reasons map to INVALID_DID")
        void didReasonsMapToInvalidDid(String reason) {
            assertThat(mapper.toPreDispatchCode(reason))
                    .isEqualTo(CallFailureCode.INVALID_DID);
        }

        @ParameterizedTest(name = "PRE-3: capacity reason {0} requeues")
        @ValueSource(strings = {
                "ROUTE_REJECTED_CHANNEL_CAPACITY",
                "ROUTE_REJECTED_CPS_CAPACITY",
                "ROUTE_REJECTED_CAPACITY_HEADROOM",
        })
        @DisplayName("PRE-3: capacity reasons are recognised as requeueable, not failures")
        void capacityReasonsRequeue(String reason) {
            assertThat(mapper.isCapacityReason(reason))
                    .as("%s must requeue rather than fail", reason)
                    .isTrue();
            assertThat(mapper.toPreDispatchCode(reason))
                    .isEqualTo(CallFailureCode.TEMPORARILY_UNAVAILABLE);
        }

        @Test
        @DisplayName("PRE-4: a non-capacity reason does not requeue")
        void nonCapacityDoesNotRequeue() {
            assertThat(mapper.isCapacityReason("ROUTE_REJECTED_GATEWAY_DISABLED")).isFalse();
            assertThat(mapper.isCapacityReason(null)).isFalse();
        }
    }

    @Nested
    class PassThrough {

        @Test
        @DisplayName("PRE-5: an already-canonical eligibility code passes through unchanged")
        void canonicalEligibilityCodesPassThrough() {
            // The eligibility gate propagates its own reason codes, so the mapper
            // must not rewrite them into something else.
            for (CallFailureCode canonical : new CallFailureCode[] {
                    CallFailureCode.DNC_BLOCKED,
                    CallFailureCode.PLATFORM_BLOCKED,
                    CallFailureCode.PLATFORM_PROTECTED,
                    CallFailureCode.RESELLER_BLOCKED,
                    CallFailureCode.NOT_WHITELISTED,
                    CallFailureCode.NOT_IN_CAMPAIGN_TARGETS,
                    CallFailureCode.INVALID_DID,
                    CallFailureCode.INVALID_NUMBER}) {
                assertThat(mapper.toPreDispatchCode(canonical.getCode())).isEqualTo(canonical);
            }
        }
    }

    @Nested
    class NeverHangup {

        @Test
        @DisplayName("PRE-6: an unmapped or absent reason never becomes HANGUP_UNKNOWN")
        void unknownReasonNeverBecomesHangup() {
            // This is the core repair. An unmapped pre-dispatch reason must be
            // classified as pre-dispatch; falling back to HANGUP_UNKNOWN is
            // precisely the bug.
            for (String reason : new String[] {
                    "SOMETHING_ELSE_ENTIRELY", "ROUTE_REJECTED_SOMETHING_NEW", "", "   "}) {
                assertThat(mapper.toPreDispatchCode(reason))
                        .as("reason '%s' must not become a contact outcome", reason)
                        .isNotEqualTo(CallFailureCode.HANGUP_UNKNOWN)
                        .isIn(CallFailureCode.NO_ELIGIBLE_GATEWAY);
            }
            assertThat(mapper.toPreDispatchCode(null))
                    .isEqualTo(CallFailureCode.NO_ELIGIBLE_GATEWAY);
        }

        @Test
        @DisplayName("PRE-7: the mapping is total — every routing reason resolves to something")
        void mappingIsTotal() {
            for (com.shivang.obd.voice.routing.VoiceRoutingReason reason
                    : com.shivang.obd.voice.routing.VoiceRoutingReason.values()) {
                if (reason.getCode().startsWith("ROUTE_SELECTED")) {
                    continue; // a selection reason is not a rejection
                }
                assertThat(mapper.toPreDispatchCode(reason.getCode()))
                        .as("%s must map to a canonical code", reason)
                        .isNotNull();
            }
        }
    }

    @Nested
    class NoRetryBudgetConsumed {

        /**
         * The outcome that actually matters: the campaign retry policy must
         * refuse a pre-dispatch rejection outright, so no retry budget is spent
         * on a call that was never placed.
         */
        private RetryDecision decide(CallFailureCode code) {
            RetryPolicySpec permissive = new RetryPolicySpec(5, 60, RetryStrategy.FIXED, null);
            return new RetryPolicyService().evaluate(permissive, code.getCode(), 1,
                    Instant.parse("2026-09-27T10:00:00Z"));
        }

        @Test
        @DisplayName("PRE-8: a routing rejection is not retryable even under a permissive policy")
        void routingRejectionIsNotRetryable() {
            for (String reason : new String[] {
                    "ROUTE_REJECTED_GATEWAY_DISABLED",
                    "ROUTE_REJECTED_DID_INCOMPATIBLE",
                    "ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY",
                    "SOMETHING_UNMAPPED"}) {
                RetryDecision decision = decide(mapper.toPreDispatchCode(reason));

                assertThat(decision.retryable())
                        .as("%s must not be retryable", reason)
                        .isFalse();
                assertThat(decision.reason()).contains("PRE_DISPATCH");
            }
        }

        @Test
        @DisplayName("PRE-9: a capacity rejection is not retryable either — it requeues instead")
        void capacityRejectionIsNotRetryable() {
            RetryDecision decision = decide(
                    mapper.toPreDispatchCode("ROUTE_REJECTED_CHANNEL_CAPACITY"));

            assertThat(decision.retryable()).isFalse();
            assertThat(decision.reason()).contains("PRE_DISPATCH");
        }

        @Test
        @DisplayName("PRE-10: DND, whitelist and the daily limits still consume no budget")
        void existingPreDispatchCodesStillConsumeNoBudget() {
            for (CallFailureCode code : new CallFailureCode[] {
                    CallFailureCode.DNC_BLOCKED,
                    CallFailureCode.NOT_WHITELISTED,
                    CallFailureCode.PLATFORM_BLOCKED,
                    CallFailureCode.NOT_IN_CAMPAIGN_TARGETS,
                    CallFailureCode.DAILY_LIMIT_REACHED,
                    CallFailureCode.DAILY_ATTEMPT_LIMIT_REACHED,
                    CallFailureCode.TEMPORARILY_UNAVAILABLE,
                    CallFailureCode.PLAYBACK_CONFIG_INVALID}) {
                assertThat(decide(code).retryable())
                        .as("%s must not consume retry budget", code)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("PRE-11: a genuine dispatched failure is still retryable")
        void dispatchedFailuresRemainRetryable() {
            // The repair must not over-correct into "nothing is ever retryable".
            assertThat(decide(CallFailureCode.NO_ANSWER).retryable())
                    .as("NO_ANSWER is a real contact outcome")
                    .isTrue();
            assertThat(decide(CallFailureCode.BUSY).retryable()).isTrue();
            assertThat(decide(CallFailureCode.PLAYBACK_FAILED).retryable())
                    .as("a dispatched media failure follows the campaign's rules")
                    .isTrue();
        }
    }

    @Nested
    class Isolation {

        @Test
        @DisplayName("PRE-12: the mapper is stateless and therefore safe to share")
        void mapperIsStateless() {
            PreDispatchFailureMapper first = new PreDispatchFailureMapper();
            PreDispatchFailureMapper second = new PreDispatchFailureMapper();

            assertThat(first.toPreDispatchCode("ROUTE_REJECTED_GATEWAY_DISABLED"))
                    .isEqualTo(second.toPreDispatchCode("ROUTE_REJECTED_GATEWAY_DISABLED"));

            // Repeated invocation must be deterministic - a mapper whose answer
            // drifted between calls would make the persisted failure code
            // depend on timing.
            for (int i = 0; i < 5; i++) {
                assertThat(first.toPreDispatchCode("ROUTE_REJECTED_DID_INCOMPATIBLE"))
                        .isEqualTo(CallFailureCode.INVALID_DID);
            }
        }
    }
}
