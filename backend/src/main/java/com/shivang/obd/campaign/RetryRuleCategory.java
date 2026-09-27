package com.shivang.obd.campaign;

/**
 * Product-level retry categories a campaign can configure (VB-6D.2).
 *
 * <p>This is a <em>policy</em> vocabulary, deliberately distinct from
 * {@link CallFailureCode}, which is the <em>provider outcome</em> vocabulary.
 * Keeping them separate is what allows many provider codes to collapse into a
 * small, stable set of rules a product owner can reason about, and it means a
 * new provider cause can be introduced without inventing new product
 * configuration.
 *
 * <p><b>Not all categories are currently reachable.</b> VB-6D.1 established
 * that the telephony boundary cannot reliably distinguish a switched-off
 * subscriber from a network-unreachable one: FreeSWITCH surfaces both only as
 * carrier-dependent SIP/Q.850 causes whose spelling varies per carrier and
 * gateway. {@link #SWITCHED_OFF} and {@link #NOT_REACHABLE} are therefore
 * retained as <em>supported configuration categories for a future reliable
 * provider mapping</em>, but today no {@link CallFailureCode} resolves to them —
 * such outcomes arrive as {@link CallFailureCode#HANGUP_UNKNOWN} and are
 * governed by {@link #HANGUP}. {@link #categoryOf(CallFailureCode)} is
 * total, so this is a documented, testable property rather than a silent gap.
 *
 * <p>{@link #FAILED} is the general-purpose bucket: any provider outcome that
 * is not specifically an unanswered call, a busy callee, or a hangup-class
 * termination. Making it the default means the mapping has no holes.
 */
public enum RetryRuleCategory {

    /** The callee never answered. The most common retryable Voice Blast outcome. */
    NO_ANSWER,

    /** The callee's line was engaged. */
    BUSY,

    /**
     * A remote party or the network ended the call without completing the
     * mission: an unmapped hangup cause, an unknown cause, or an explicit
     * caller-side hangup. Also the destination where an unclassifiable
     * provider outcome currently lands ({@link CallFailureCode#HANGUP_UNKNOWN}).
     */
    HANGUP,

    /**
     * Any other failure: network, provider, media, routing, capacity, and
     * pre-acceptance originate failures. The general-purpose bucket and the
     * default for every code without a more specific category.
     */
    FAILED,

    /**
     * Reserved for a reliable provider signal that the subscriber is
     * permanently switched off. <b>Not currently detectable</b> — see the
     * class javadoc. No canonical failure code maps here today.
     */
    SWITCHED_OFF,

    /**
     * Reserved for a reliable provider signal that the destination is not
     * reachable on the network. <b>Not currently detectable</b> — see the
     * class javadoc. No canonical failure code maps here today.
     */
    NOT_REACHABLE
}
