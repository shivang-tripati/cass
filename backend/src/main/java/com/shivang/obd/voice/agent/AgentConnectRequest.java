package com.shivang.obd.voice.agent;

import java.util.UUID;

/**
 * The frozen, per-call parameters of one CONNECT_BY_AGENT request (VB-7A).
 *
 * <p>Carried from the execution's immutable snapshot into the connect boundary,
 * so the connection service can honour a campaign's configured scope without
 * ever reading mutable campaign configuration (the VB-6A snapshot rule).
 *
 * <h2>Every field is nullable on purpose</h2>
 *
 * <p>{@code null} means "this call has no CONNECT_BY_AGENT campaign
 * configuration", and the boundary then behaves exactly as it did before VB-7A.
 * That is the normal case for a DTMF or IVR campaign whose terminal action is
 * {@code CONNECT_BY_AGENT}: the action is a property of the interaction, not of
 * the campaign type, so such a campaign has no CONNECT_BY_AGENT type config at
 * all and keeps the tenant-wide VB-3 selection. A value is only present when
 * the campaign being executed actually <em>is</em> a CONNECT_BY_AGENT campaign
 * with a configured queue.
 *
 * @param queueId            the campaign's configured queue, or {@code null}
 *                           for the tenant-wide path
 * @param ringDurationSeconds the configured ring window, or {@code null} for the
 *                           platform default ({@link AgentRingWindow#DEFAULT_RING_SECONDS})
 */
public record AgentConnectRequest(UUID queueId, Integer ringDurationSeconds) {

    /** No campaign-level configuration: the pre-VB-7A tenant-wide path. */
    public static AgentConnectRequest unscoped() {
        return new AgentConnectRequest(null, null);
    }

    /** Whether this request names a queue, i.e. must go through ACD. */
    public boolean isQueueScoped() {
        return queueId != null;
    }

    /** The effective ring window, never null and always inside the supported range. */
    public int effectiveRingSeconds() {
        return AgentRingWindow.effectiveSeconds(ringDurationSeconds);
    }
}
