package com.shivang.obd.voice.inbound;

import java.util.Optional;
import java.util.UUID;

/**
 * Inbound-call event boundary (VB-4D).
 * <p>
 * The telephony event service ({@code EslEventService}) invokes this
 * boundary when ESL events belong to an inbound (canonical
 * {@code CONTACT_CENTER_INBOUND}) call: CHANNEL_CREATE on a fresh inbound
 * channel and ANSWER/HANGUP on the inbound caller leg. The implementation
 * ({@code InboundCallService}) owns the lifecycle. Agent-leg events and
 * CHANNEL_BRIDGE confirmations are session-agnostic and continue to flow
 * through the existing shared VB-3 boundary — there is exactly ONE
 * agent-hangup and ONE bridge-confirmation implementation.
 * <p>
 * All methods must be idempotent and must not throw: duplicate events
 * (same provider UUID delivered twice) are no-ops, and unroutable calls
 * are ignored safely (fail closed).
 */
public interface InboundCallEvents {

    /**
     * A new inbound channel appeared (CHANNEL_CREATE with
     * Call-Direction=inbound): resolve DID → tenant → destination, create
     * the canonical CallSession + CUSTOMER leg, and route (queue entry or
     * direct agent). Idempotent by the provider channel UUID: a duplicate
     * event returns the existing session id instead of creating a second.
     *
     * @param channelUuid       FreeSWITCH channel UUID of the inbound caller
     * @param destinationNumber the DID the caller dialed (may be null)
     * @param callerNumber      the caller's CLI (may be null)
     * @return the canonical session id, or empty when the event is not
     *         routable (no/unowned/misconfigured DID) — never throws
     */
    Optional<UUID> onInboundChannelCreated(
            String channelUuid, String destinationNumber, String callerNumber);

    /**
     * The inbound caller channel answered (CHANNEL_ANSWER on the CUSTOMER
     * leg of an inbound session). Idempotent: late/duplicate answers
     * never regress session state.
     */
    void onInboundCallerAnswered(com.shivang.obd.voice.call.CallLeg callerLeg);

    /**
     * The inbound caller channel hung up (CHANNEL_HANGUP) — the
     * authoritative end of an inbound call at any stage (queued, agent
     * ringing, bridged). Removes the queue entry, releases the agent
     * reservation, tears down the agent leg, finalizes the session.
     * Idempotent.
     *
     * @param hangupCause the FreeSWITCH hangup cause (may be null)
     */
    void onInboundCallerHangup(com.shivang.obd.voice.call.CallLeg callerLeg,
                               String hangupCause);

    /**
     * ACD assigned this waiting call (queue path): create the AGENT leg
     * for the assigned agent and originate it through the existing VB-3
     * dialing boundary. The reservation already exists (ACD holds it);
     * originate failure releases it. Idempotent: an existing agent leg
     * short-circuits. Returns false when the call is not connectable.
     */
    boolean connectAssignedAgent(UUID waitingCallId);
}
