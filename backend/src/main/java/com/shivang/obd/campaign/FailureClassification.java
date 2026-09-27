package com.shivang.obd.campaign;

import java.util.EnumSet;
import java.util.Set;

/**
 * The single boundary between a canonical provider outcome and the campaign
 * retry policy (VB-6D.2).
 *
 * <p>Every retry decision reduces to two questions, asked here and nowhere
 * else, so no switch statement has to be repeated across the orchestrator,
 * the dial service, the ESL handlers, or the attempt services:
 *
 * <ol>
 *   <li><b>Was the contact actually attempted?</b> {@link Disposition} —
 *       {@link Disposition#PRE_DISPATCH} means the call was never placed, so
 *       there is nothing to retry <em>and</em> no retry budget to spend;
 *       {@link Disposition#CONTACT_OUTCOME} means a call was placed and the
 *       campaign's own rules decide.</li>
 *   <li><b>Which product rule applies?</b> {@link RetryRuleCategory} — the
 *       stable, product-facing bucket a campaign owner configures.</li>
 * </ol>
 *
 * <p><b>Why pre-dispatch failures are excluded from campaign rules.</b> A
 * number on a DND list, an unapproved asset, a missing execution snapshot, or
 * an exhausted daily bucket cannot be fixed by dialling again: the next
 * attempt would fail identically. Worse, if such a rejection consumed retry
 * budget it would burn a contact's entire allowance on an outcome that was
 * never a call. Campaign policy is therefore able to <em>restrict</em> retries,
 * never to manufacture one for a pre-dispatch rejection. Note the asymmetry is
 * deliberate: capacity and provider-unavailable rejections are also
 * pre-dispatch, but the existing orchestrator <em>requeues</em> them (the
 * attempt returns to {@code QUEUED} with its attempt number intact) rather
 * than failing them, so they never reach a retry decision at all and their
 * budget is untouched by construction.
 *
 * <p><b>Totality.</b> {@link #of(String)} is total: null, blank, and
 * unrecognized values resolve deterministically to
 * {@link CallFailureCode#HANGUP_UNKNOWN} (via
 * {@link CallFailureCode#canonicalize(String)}) and are then classified as an
 * ordinary {@link RetryRuleCategory#HANGUP} contact outcome. An arbitrary
 * provider string can therefore never decide whether a contact is re-dialled.
 */
public record FailureClassification(Disposition disposition, RetryRuleCategory category) {

    /** Whether a failed attempt represents a real attempt at the contact. */
    public enum Disposition {
        /**
         * The call was never placed. Governed by no campaign rule, and it
         * consumes no retry budget. Deterministic on the canonical code, not on
         * campaign configuration.
         */
        PRE_DISPATCH,
        /**
         * A call was placed and produced an outcome. Governed by the campaign's
         * per-category retry rules, and it consumes retry budget.
         */
        CONTACT_OUTCOME
    }

    /**
     * Canonical outcomes that mean "the call was never placed". Every one of
     * these describes the state of the <em>system</em> at dispatch time, not
     * the state of the <em>contact</em>.
     *
     * <p>Grouped by why, so the set stays reviewable:
     * <ul>
     *   <li>compliance / eligibility — the destination must not be dialled;</li>
     *   <li>configuration integrity — the execution or its assets are broken;</li>
     *   <li>admission — routing or a safety budget refused the dispatch;</li>
     *   <li>pre-acceptance originate — the provider never accepted a call.</li>
     * </ul>
     */
    private static final Set<CallFailureCode> PRE_DISPATCH = EnumSet.of(
            // compliance / eligibility (VoiceEligibility, CallEligibility)
            CallFailureCode.INVALID_DID,
            CallFailureCode.INVALID_NUMBER,
            CallFailureCode.PLATFORM_BLOCKED,
            CallFailureCode.PLATFORM_PROTECTED,
            CallFailureCode.RESELLER_BLOCKED,
            CallFailureCode.DNC_BLOCKED,
            CallFailureCode.NOT_WHITELISTED,
            CallFailureCode.NOT_IN_CAMPAIGN_TARGETS,
            // configuration integrity
            CallFailureCode.CAMPAIGN_NOT_FOUND,
            CallFailureCode.EXECUTION_CONFIG_MISSING,
            CallFailureCode.EXECUTION_TIMEZONE_INVALID,
            CallFailureCode.CONTACT_INVALID,
            CallFailureCode.PLAYBACK_CONFIG_INVALID,
            CallFailureCode.DTMF_CONFIG_INVALID,
            CallFailureCode.CONNECT_BY_AGENT_UNSUPPORTED,
            CallFailureCode.AGENT_CONFIG_INVALID,
            CallFailureCode.AGENT_ENDPOINT_INVALID,
            CallFailureCode.AGENT_TENANT_MISMATCH,
            // admission (routing / safety budget)
            CallFailureCode.NO_ELIGIBLE_GATEWAY,
            CallFailureCode.TEMPORARILY_UNAVAILABLE,
            CallFailureCode.DAILY_LIMIT_REACHED,
            CallFailureCode.DAILY_ATTEMPT_LIMIT_REACHED,
            // pre-acceptance originate: the provider never accepted a call
            CallFailureCode.DIAL_FAILED,
            CallFailureCode.PROVIDER_UNAVAILABLE,
            CallFailureCode.GATEWAY_CAPACITY_EXHAUSTED,
            CallFailureCode.CALL_ORIGINATE_FAILED,
            CallFailureCode.ROUTE_REJECTED,
            CallFailureCode.INBOUND_ROUTE_INVALID,
            CallFailureCode.INVALID_DESTINATION,
            CallFailureCode.AGENT_ORIGINATE_FAILED);

    /**
     * Codes whose meaning is specifically "the callee did not answer".
     */
    private static final Set<CallFailureCode> NO_ANSWER = EnumSet.of(
            CallFailureCode.NO_ANSWER,
            CallFailureCode.AGENT_NO_ANSWER);

    /**
     * Codes whose meaning is specifically "the callee's line was engaged".
     */
    private static final Set<CallFailureCode> BUSY = EnumSet.of(
            CallFailureCode.BUSY,
            CallFailureCode.AGENT_BUSY);

    /**
     * Codes whose meaning is specifically "the call was terminated by a remote
     * party or the network without completing": an unmapped cause
     * ({@code HANGUP_UNKNOWN}), an explicit caller-side hangup, or a rejection.
     *
     * <p>{@code REJECTED} is grouped here because the remote end actively
     * terminated the call rather than the attempt failing locally. It is
     * {@link CallFailureCode.RetryClass#PERMANENT} regardless, so this choice
     * is a label, never a permission.
     */
    private static final Set<CallFailureCode> HANGUP = EnumSet.of(
            CallFailureCode.HANGUP_UNKNOWN,
            CallFailureCode.CALLER_HANGUP,
            CallFailureCode.REJECTED);

    /**
     * Classifies a persisted failure code. Total and deterministic: the code is
     * canonicalized first, so an absent or unrecognized value is treated as
     * {@link CallFailureCode#HANGUP_UNKNOWN} rather than falling through.
     */
    public static FailureClassification of(String persistedFailureCode) {
        return of(CallFailureCode.canonicalize(persistedFailureCode));
    }

    /** Classifies an already-canonical code. */
    public static FailureClassification of(CallFailureCode code) {
        CallFailureCode canonical = code == null ? CallFailureCode.HANGUP_UNKNOWN : code;

        if (PRE_DISPATCH.contains(canonical)) {
            return new FailureClassification(Disposition.PRE_DISPATCH, null);
        }
        return new FailureClassification(Disposition.CONTACT_OUTCOME, categoryOf(canonical));
    }

    /**
     * The product category for a contact outcome. {@link RetryRuleCategory#FAILED}
     * is the default so the mapping has no holes: a newly added provider code
     * lands in the general bucket instead of becoming ungoverned.
     *
     * <p>Never returns {@link RetryRuleCategory#SWITCHED_OFF} or
     * {@link RetryRuleCategory#NOT_REACHABLE} today — see
     * {@link RetryRuleCategory}'s javadoc.
     */
    public static RetryRuleCategory categoryOf(CallFailureCode code) {
        CallFailureCode canonical = code == null ? CallFailureCode.HANGUP_UNKNOWN : code;
        if (NO_ANSWER.contains(canonical)) {
            return RetryRuleCategory.NO_ANSWER;
        }
        if (BUSY.contains(canonical)) {
            return RetryRuleCategory.BUSY;
        }
        if (HANGUP.contains(canonical)) {
            return RetryRuleCategory.HANGUP;
        }
        return RetryRuleCategory.FAILED;
    }

    public Disposition disposition() {
        return disposition;
    }

    public boolean isPreDispatch() {
        return disposition == Disposition.PRE_DISPATCH;
    }

    public boolean isContactOutcome() {
        return disposition == Disposition.CONTACT_OUTCOME;
    }

    /**
     * The governing product category, or {@code null} for a pre-dispatch
     * rejection (no category governs it).
     */
    public RetryRuleCategory category() {
        return category;
    }

    /**
     * A record, so it compares by value. That matters beyond tidiness: the
     * {@link RetryDecision} that carries it is a record too, and a
     * decision is only meaningfully "deterministic" if two evaluations of the
     * same inputs are {@code equal}. With reference identity here, that
     * property would silently not hold.
     */
    @Override
    public String toString() {
        return "FailureClassification[" + disposition
                + (category == null ? "" : ", " + category)
                + "]";
    }
}
