package com.shivang.obd.campaign;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps a routing rejection reason onto a canonical pre-dispatch
 * {@link CallFailureCode} (VB-6E).
 *
 * <h2>The defect this repairs</h2>
 *
 * <p>When routing rejected a call, {@code OutboundDialService} persisted the
 * raw {@code VoiceRoutingReason} name — for example
 * {@code ROUTE_REJECTED_GATEWAY_DISABLED} — straight into
 * {@code call_attempts.failure_code}. Those names are not members of
 * {@link CallFailureCode}, so {@code CallFailureCode.canonicalize} mapped every
 * one of them to {@code HANGUP_UNKNOWN}, which classifies as
 * {@code CONTACT_OUTCOME}/{@code HANGUP}. A gateway being administratively
 * disabled was therefore treated as a hangup by the contact, consumed campaign
 * retry budget, and could re-dial a number that was never called.
 *
 * <p>That directly contradicted the VB-6D design, where
 * {@code CallFailureCode.ROUTE_REJECTED} sits in
 * {@link FailureClassification#PRE_DISPATCH} precisely so routing rejections
 * consume no budget — and the pre-VB-6E code never wrote that constant at all,
 * leaving it dead.
 *
 * <h2>The contract</h2>
 *
 * <p>Every reason produced <em>before</em> a provider dial can map to a code in
 * the pre-dispatch set, so the VB-6D retry policy refuses it without consuming
 * budget. Mapping is total and closed:
 *
 * <ul>
 *   <li>a reason that is already a canonical {@link CallFailureCode} name — the
 *       eligibility gate propagates {@code DNC_BLOCKED},
 *       {@code NOT_WHITELISTED}, {@code INVALID_DID} and friends — passes
 *       through unchanged;</li>
 *   <li>capacity reasons map to {@code TEMPORARILY_UNAVAILABLE}, which is also
 *       pre-dispatch;</li>
 *   <li>DID and authorization reasons map to {@code INVALID_DID};</li>
 *   <li>everything else — no profile, nothing eligible, policy-disabled
 *       overflow/failover — maps to {@code NO_ELIGIBLE_GATEWAY}.</li>
 * </ul>
 *
 * <p>An unknown or {@code null} reason maps to
 * {@link CallFailureCode#NO_ELIGIBLE_GATEWAY} rather than to
 * {@code HANGUP_UNKNOWN}: an unmapped <em>pre-dispatch</em> reason must never
 * be dressed up as a post-dispatch contact outcome, which is the precise
 * failure mode being repaired.
 *
 * <p>Deliberately a component rather than static methods: it is the
 * campaign-side authority for this boundary and is unit-testable in isolation.
 */
@Component
public class PreDispatchFailureMapper {

    /**
     * Routing reasons that mean "no route could carry this call for a reason
     * that is not the contact's fault and not transient capacity".
     */
    private static final Map<String, CallFailureCode> ROUTING_REASON_TO_CODE = Map.ofEntries(
            // No capacity anywhere in the profile.
            Map.entry("ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY", CallFailureCode.NO_ELIGIBLE_GATEWAY),
            Map.entry("ROUTE_REJECTED_NO_PROFILE_CONFIGURED", CallFailureCode.NO_ELIGIBLE_GATEWAY),
            Map.entry("ROUTE_REJECTED_NO_APPROVED_OVERFLOW", CallFailureCode.NO_ELIGIBLE_GATEWAY),
            Map.entry("ROUTE_REJECTED_NO_APPROVED_FAILOVER", CallFailureCode.NO_ELIGIBLE_GATEWAY),
            Map.entry("ROUTE_REJECTED_UNKNOWN", CallFailureCode.NO_ELIGIBLE_GATEWAY),

            // Capacity — pre-dispatch and transient, never a contact outcome.
            Map.entry("ROUTE_REJECTED_CHANNEL_CAPACITY", CallFailureCode.TEMPORARILY_UNAVAILABLE),
            Map.entry("ROUTE_REJECTED_CPS_CAPACITY", CallFailureCode.TEMPORARILY_UNAVAILABLE),
            Map.entry("ROUTE_REJECTED_CAPACITY_HEADROOM", CallFailureCode.TEMPORARILY_UNAVAILABLE),

            // Gateway state — administrative, so pre-dispatch and not the contact's.
            Map.entry("ROUTE_REJECTED_GATEWAY_DISABLED", CallFailureCode.NO_ELIGIBLE_GATEWAY),
            Map.entry("ROUTE_REJECTED_GATEWAY_INACTIVE", CallFailureCode.NO_ELIGIBLE_GATEWAY),
            Map.entry("ROUTE_REJECTED_GATEWAY_DEGRADED", CallFailureCode.NO_ELIGIBLE_GATEWAY),
            Map.entry("ROUTE_REJECTED_GATEWAY_MAINTENANCE", CallFailureCode.NO_ELIGIBLE_GATEWAY),
            Map.entry("ROUTE_REJECTED_GATEWAY_UNHEALTHY", CallFailureCode.NO_ELIGIBLE_GATEWAY),

            // DID / authorization — a configuration and tenancy problem.
            Map.entry("ROUTE_REJECTED_DID_INCOMPATIBLE", CallFailureCode.INVALID_DID),
            Map.entry("ROUTE_REJECTED_DID_NOT_ALLOWED", CallFailureCode.INVALID_DID),
            Map.entry("ROUTE_REJECTED_INVALID_DID", CallFailureCode.INVALID_DID),
            Map.entry("ROUTE_REJECTED_TENANT_NOT_AUTHORIZED", CallFailureCode.INVALID_DID),
            Map.entry("ROUTE_REJECTED_RESELLER_NOT_AUTHORIZED", CallFailureCode.INVALID_DID),
            Map.entry("ROUTE_REJECTED_ENTERPRISE_ONLY", CallFailureCode.INVALID_DID),

            // Policy — the tenant's own routing policy declined the route.
            Map.entry("ROUTE_REJECTED_AUTO_OVERFLOW_DISABLED", CallFailureCode.NO_ELIGIBLE_GATEWAY),
            Map.entry("ROUTE_REJECTED_AUTO_FAILOVER_DISABLED", CallFailureCode.NO_ELIGIBLE_GATEWAY),
            Map.entry("ROUTE_REJECTED_CAMPAIGN_NOT_ALLOWED", CallFailureCode.NO_ELIGIBLE_GATEWAY),

            // Eligibility-shaped reasons. Routing never emits these today
            // (eligibility short-circuits first) but mapping them keeps the
            // boundary total if that ever changes.
            Map.entry("ROUTE_REJECTED_BLOCKLIST", CallFailureCode.PLATFORM_BLOCKED),
            Map.entry("ROUTE_REJECTED_DNC", CallFailureCode.DNC_BLOCKED),
            Map.entry("ROUTE_REJECTED_WHITELIST", CallFailureCode.NOT_WHITELISTED));

    /**
     * The canonical pre-dispatch code for a routing/eligibility rejection
     * reason. Never returns {@code null} and never returns a code outside the
     * pre-dispatch set.
     */
    public CallFailureCode toPreDispatchCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return CallFailureCode.NO_ELIGIBLE_GATEWAY;
        }
        String reason = reasonCode.trim();

        // Already canonical: the eligibility gate propagates its own codes.
        CallFailureCode direct = CallFailureCode.fromCode(reason).orElse(null);
        if (direct != null) {
            return direct;
        }

        CallFailureCode mapped = ROUTING_REASON_TO_CODE.get(reason);
        if (mapped != null) {
            return mapped;
        }

        // Unknown reason. Pre-dispatch by construction, so it must be
        // classified as such — never as HANGUP_UNKNOWN, which would turn a
        // call that was never placed into a retryable contact outcome.
        return CallFailureCode.NO_ELIGIBLE_GATEWAY;
    }

    /**
     * True when the reason means "the gateway is momentarily out of capacity",
     * in which case the attempt is requeued rather than failed. Reusing the
     * existing rule keeps the requeue decision and the persisted code in
     * agreement, instead of two independent string comparisons that can drift.
     */
    public boolean isCapacityReason(String reasonCode) {
        if (reasonCode == null) {
            return false;
        }
        return toPreDispatchCode(reasonCode) == CallFailureCode.TEMPORARILY_UNAVAILABLE;
    }
}
