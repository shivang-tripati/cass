package com.shivang.obd.campaign;

import java.util.Arrays;
import java.util.Optional;

/**
 * Canonical failure-code taxonomy for persisted call outcomes (VB-6A).
 * <p>
 * Consolidates every machine-readable {@code failureCode} value the codebase
 * actually assigns to {@code CallAttempt}, {@code CallSession}, and
 * {@code CallLeg} rows (inventory taken from all {@code setFailureCode}
 * producers: dial pipeline, ESL hangup mapping, playback/DTMF/agent
 * execution services, inbound and agent-outbound boundaries).
 * <p>
 * The retry classification is <strong>behavior-preserving by definition</strong>:
 * {@link RetryClass#PERMANENT} is carried exactly by the codes the
 * pre-existing {@code CampaignExecutionOrchestrator.isPermanentFailure} gate
 * never retried (VB-1/VB-2 configuration and hard dial failures);
 * {@link RetryClass#TEMPORARY} covers every other code, which the existing
 * retry policy has always retried. This phase is taxonomy consolidation
 * only — no retry policy change. Per-case semantic classification
 * (e.g. making eligibility rejections permanent) is a future policy decision
 * and deliberately NOT pre-baked here.
 * <p>
 * Codes live in one enum so producers and consumers reference a single
 * domain vocabulary; persistence stays string-based (existing columns), so
 * historical values remain readable and {@link #fromCode} resolves unknown
 * or forward-compat values to {@link Optional#empty()} — callers classify
 * them deterministically (unknown = temporary under the current gate,
 * exactly as the legacy predicate did) and nothing crashes.
 */
public enum CallFailureCode {

    // === Dial pipeline results (OutboundDialService / AgentOutboundCallService) ===

    /** Destination was busy (dial result or hangup cause 17/USER_BUSY). */
    BUSY(RetryClass.TEMPORARY),

    /** Destination never answered (dial result or hangup cause 19). */
    NO_ANSWER(RetryClass.TEMPORARY),

    /** Call rejected by the destination or network (cause 21 / dial REJECTED). */
    REJECTED(RetryClass.PERMANENT),

    /** Dial request could not be completed (dial FAILED → DIAL_FAILED). */
    DIAL_FAILED(RetryClass.PERMANENT),

    /** Provider/telephony infrastructure unavailable — requeued, not failed. */
    PROVIDER_UNAVAILABLE(RetryClass.TEMPORARY),

    /** Congestion / no circuit available (hangup cause 34). */
    CONGESTION(RetryClass.TEMPORARY),

    /** Normal temporary failure (hangup cause 41). */
    TEMPORARY_FAILURE(RetryClass.TEMPORARY),

    /** Requested resource unavailable (hangup cause 47). */
    RESOURCE_UNAVAILABLE(RetryClass.TEMPORARY),

    /** Unrecognized or missing hangup cause. */
    HANGUP_UNKNOWN(RetryClass.TEMPORARY),

    // === In-call media failures (EslEventService / PlayfileExecutionService) ===

    /** Media playback failed for a transient (resource/media) reason. */
    PLAYBACK_FAILED(RetryClass.TEMPORARY),

    /** Playback impossible due to campaign/asset configuration — permanent. */
    PLAYBACK_CONFIG_INVALID(RetryClass.PERMANENT),

    /** DTMF configuration invalid — permanent; retrying cannot succeed. */
    DTMF_CONFIG_INVALID(RetryClass.PERMANENT),

    /** DTMF playback command failed — transient. */
    DTMF_PLAYBACK_FAILED(RetryClass.TEMPORARY),

    // === Reserved policy codes (never assigned by current producers) ===

    /**
     * Reserved by the permanent-failure gate: CONNECT_BY_AGENT is
     * structurally unsupported for the campaign type. No current producer
     * assigns this code; it exists so the legacy gate is fully representable.
     */
    CONNECT_BY_AGENT_UNSUPPORTED(RetryClass.PERMANENT),

    // === Agent connect (VB-3) / inbound (VB-4D) failures ===

    /** Agent leg could not be originated after a successful reservation. */
    AGENT_ORIGINATE_FAILED(RetryClass.TEMPORARY),

    /** Agent leg rang but hung up before the bridge. */
    AGENT_NO_ANSWER(RetryClass.TEMPORARY),

    /** Agent left the conversation after a confirmed bridge. */
    AGENT_LEFT(RetryClass.TEMPORARY),

    /** Bridging caller and agent failed after the agent answered. */
    AGENT_BRIDGE_FAILED(RetryClass.TEMPORARY),

    /** The connect flow could not complete (agent hung up before bridge). */
    AGENT_CONNECT_FAILED(RetryClass.TEMPORARY),

    /** Campaign/DTMF configuration requests an unusable agent setup. */
    AGENT_CONFIG_INVALID(RetryClass.PERMANENT),

    /** Selected agent has no enabled, dialable endpoint. */
    AGENT_ENDPOINT_INVALID(RetryClass.PERMANENT),

    /** Cross-tenant agent reference — fail closed. */
    AGENT_TENANT_MISMATCH(RetryClass.PERMANENT),

    /** All eligible agents busy or reservation lost to a concurrent caller. */
    AGENT_BUSY(RetryClass.TEMPORARY),

    /** Tenant has no agents at all / none currently available. */
    AGENT_UNAVAILABLE(RetryClass.TEMPORARY),

    /** Caller disconnected before/independent of the flow outcome. */
    CALLER_HANGUP(RetryClass.TEMPORARY),

    // === Eligibility rejections (VoiceEligibility / CallEligibility) ===

    /** No DID assigned / DID not found / inactive / unassigned / foreign. */
    INVALID_DID(RetryClass.TEMPORARY),

    /** Number violates E.164 normalization. */
    INVALID_NUMBER(RetryClass.TEMPORARY),

    /** Platform blocklist hit. */
    PLATFORM_BLOCKED(RetryClass.TEMPORARY),

    /** Platform protected number. */
    PLATFORM_PROTECTED(RetryClass.TEMPORARY),

    /** Reseller blocklist hit. */
    RESELLER_BLOCKED(RetryClass.TEMPORARY),

    /** Tenant DNC/blocklist hit. */
    DNC_BLOCKED(RetryClass.TEMPORARY),

    /** Whitelist enforcement enabled and number not whitelisted. */
    NOT_WHITELISTED(RetryClass.TEMPORARY),

    /** Number not a member of the campaign contact group. */
    NOT_IN_CAMPAIGN_TARGETS(RetryClass.TEMPORARY),

    /** No compatible gateway for the DID provider. */
    NO_ELIGIBLE_GATEWAY(RetryClass.TEMPORARY),

    /** No available gateway capacity right now — requeued, not failed. */
    TEMPORARILY_UNAVAILABLE(RetryClass.TEMPORARY),        // === Orchestration / routing rejections (OutboundDialService) ===

    /** Campaign no longer exists for the attempt's denormalized campaignId. */
    CAMPAIGN_NOT_FOUND(RetryClass.PERMANENT),

    /**
     * The execution's configuration snapshot could not be resolved (VB-6A
     * correction). Data-integrity violation — no retry can succeed.
     */
    EXECUTION_CONFIG_MISSING(RetryClass.PERMANENT),

    /**
     * The attempt's contact is no longer valid for dialing (VB-6B.1):
     * missing, soft-deleted, owned by another tenant, or no longer in the
     * execution's audience group. The audience was fixed at execution
     * start; retrying cannot restore the contact, so this is permanent.
     */
    CONTACT_INVALID(RetryClass.PERMANENT),

    /**
     * The execution's configuration snapshot carries no valid IANA
     * timezone, so the daily dial-limit usage day is undefined (VB-6C.1).
     * Deliberately fails the dial instead of falling back to JVM/UTC;
     * fixing the campaign configuration is the only remedy, so retrying
     * cannot succeed.
     */
    EXECUTION_TIMEZONE_INVALID(RetryClass.PERMANENT),

    /** Routing failed for a non-capacity reason. */
    ROUTE_REJECTED(RetryClass.TEMPORARY),

    /**
     * The Voice Blast daily dial-limit bucket
     * {@code (tenant, contact, actual outbound DNID, usage day)} is at its
     * effective limit (VB-6C.1): the provider was never contacted, so no
     * slot was consumed by this attempt. Classified PERMANENT on purpose:
     * a same-day retry with the same DNID can never succeed (the bucket
     * only empties at the next calendar day or via a different DNID), and
     * the existing permanent-failure retry gate is exactly the mechanism
     * that prevents a same-day retry loop — while a future day, or a
     * route resolving a different DNID, starts from a fresh bucket.
     */
    DAILY_LIMIT_REACHED(RetryClass.PERMANENT),

    // === Inbound / agent-outbound boundaries (VB-4D / VB-4E) ===

    /** No usable inbound route for the called DID. */
    INBOUND_ROUTE_INVALID(RetryClass.TEMPORARY),

    /** Destination missing, blank, or not a structurally valid E.164 number. */
    INVALID_DESTINATION(RetryClass.TEMPORARY),

    /** Gateway channel/CPS capacity reservation failed for an agent call. */
    GATEWAY_CAPACITY_EXHAUSTED(RetryClass.TEMPORARY),

    /** Agent-outbound customer-leg originate failed synchronously. */
    CALL_ORIGINATE_FAILED(RetryClass.TEMPORARY);

    /**
     * Retry classification under the current policy — identical to the
     * pre-existing permanent-failure gate (taxonomy consolidation, not a
     * policy change).
     */
    public enum RetryClass { TEMPORARY, PERMANENT }

    private final RetryClass retryClass;

    CallFailureCode(RetryClass retryClass) {
        this.retryClass = retryClass;
    }

    public RetryClass getRetryClass() {
        return retryClass;
    }

    public String getCode() {
        return name();
    }

    /**
     * Deterministic lookup of the enum constant for a persisted code.
     * Unknown historical or forward-compat values yield
     * {@link Optional#empty()} — callers decide policy; nothing crashes.
     */
    public static Optional<CallFailureCode> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(c -> c.name().equals(code))
                .findFirst();
    }

    /**
     * Resolves the retry classification for a persisted failure code under
     * the current policy. Null and unknown codes classify as
     * {@link RetryClass#TEMPORARY}, exactly matching the legacy predicate,
     * where only the enumerated permanent codes were ever withheld from
     * retry.
     */
    public static RetryClass retryClassOf(String code) {
        return isPermanent(code)
                ? RetryClass.PERMANENT
                : RetryClass.TEMPORARY;
    }

    /**
     * Whether the given persisted failure code is permanent (never retried)
     * under the current policy — the exact legacy gate semantics: only the
     * {@link RetryClass#PERMANENT} constants are permanent; null, unknown,
     * and every temporary code retry as before.
     */
    public static boolean isPermanent(String code) {
        return fromCode(code)
                .map(c -> c.getRetryClass() == RetryClass.PERMANENT)
                .orElse(false);
    }
}
