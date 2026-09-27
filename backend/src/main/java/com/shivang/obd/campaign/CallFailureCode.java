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
 * or forward-compat values to {@link Optional#empty()}.
 * <p>
 * <strong>VB-6D.1 — the persistence boundary is now closed.</strong> Every
 * provider/telephony failure outcome is translated to a canonical constant by
 * {@code com.shivang.obd.voice.call.HangupCauseMapper} before it reaches
 * {@code failureCode}. That mapper is total and closed: it never echoes
 * provider text, so values such as {@code "HANGUP_" + cause} — which
 * {@link #fromCode} could not resolve and which therefore fell through to the
 * permissive branch of the retry gate — can no longer be produced. Consumers
 * that need to act on a code use {@link #canonicalize(String)}, which always
 * yields a canonical constant ({@link #HANGUP_UNKNOWN} for anything else),
 * so classification never depends on an empty lookup.
 * <p>
 * <strong>Not modelled, deliberately.</strong> Carrier-specific categories
 * (switched-off, network-unreachable) are absent: the telephony boundary
 * exposes them only as provider-dependent SIP/Q.850 causes whose spelling
 * varies per carrier, so any mapping would be a guess. They are a future
 * policy decision (VB-6D audit OD-2) and today resolve to
 * {@link #HANGUP_UNKNOWN}.
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

    /**
     * Canonical catch-all for a hangup whose cause carries no usable
     * classification, including an absent or provider-specific cause the
     * telephony boundary does not recognise. VB-6D.1: this is now the
     * <em>only</em> result for an unmapped cause, so a provider string can
     * never become a business failure code, and every such outcome is
     * classified by this one reviewed constant.
     */
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

    /**
     * The Voice Blast daily campaign-attempt ceiling
     * {@code (tenant, contact, calendar day)} — shared across every Voice
     * Blast campaign of the tenant — was reached (VB-6D.3).
     *
     * <p>PERMANENT for the same reason as {@link #DAILY_LIMIT_REACHED}: this
     * attempt consumed nothing (the conditional UPDATE granted no slot), and
     * the same day can only begin again at the next calendar day in the
     * execution snapshot's timezone. A same-day retry could not succeed, so
     * the permanence class is what stops a same-day retry loop from burning
     * the schedule.
     *
     * <p>Distinct from {@link #DAILY_LIMIT_REACHED}: that one is scoped to
     * the actual routed DNID and is released if the dial is not accepted;
     * this one is DNID-agnostic and is consumed at dispatch. See
     * {@link DailyAttemptSafetyService}.
     */
    DAILY_ATTEMPT_LIMIT_REACHED(RetryClass.PERMANENT),

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
     * Resolves the retry classification for a persisted failure code under the
     * current policy, via {@link #canonicalize(String)}.
     * <p>
     * Behaviour for every canonical code is unchanged from the legacy gate. An
     * absent or non-canonical value is classified as
     * {@link #HANGUP_UNKNOWN} rather than falling through an implicit
     * default, so the unresolved case is now an explicit, named policy that is
     * changeable in exactly one place instead of an accident of an empty
     * lookup.
     */
    public static RetryClass retryClassOf(String code) {
        return canonicalize(code).getRetryClass();
    }

    /**
     * Whether the given persisted failure code is permanent (never retried)
     * under the current policy. Delegates to {@link #retryClassOf(String)} so
     * the retry gate and any future consumer share one classification path.
     */
    public static boolean isPermanent(String code) {
        return retryClassOf(code) == RetryClass.PERMANENT;
    }

    /**
     * Resolves any persisted failure string to a canonical constant, never
     * {@code null} (VB-6D.1).
     * <p>
     * This is the single entry point consumers must use when they need to
     * <em>act</em> on a persisted code. A value that is absent, blank, or not
     * a canonical constant historically resolves to {@link #HANGUP_UNKNOWN},
     * the canonical "unrecognized or missing hangup cause" code. That case was
     * reachable because the telephony adapters once persisted
     * provider-derived {@code "HANGUP_" + cause} strings; the adapters no
     * longer emit them (see {@code HangupCauseMapper}), and this method keeps
     * the resolution safe for any value that still exists.
     * <p>
     * The distinction matters: {@link #fromCode} answers "is this a known
     * code?" and legitimately returns empty; this method answers "which
     * canonical code does this mean?" and always has an answer. A caller that
     * needs to branch on meaning must never branch on emptiness.
     */
    public static CallFailureCode canonicalize(String code) {
        return fromCode(code).orElse(HANGUP_UNKNOWN);
    }
}
