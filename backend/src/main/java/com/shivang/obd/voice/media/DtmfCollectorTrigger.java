package com.shivang.obd.voice.media;

import java.util.UUID;

/**
 * Campaign-side DTMF interaction boundary (VB-2).
 * <p>
 * The telephony event service ({@code EslEventService}) invokes this
 * boundary when a DTMF digit arrives on a call or a collection window
 * expires; the campaign-side implementation ({@code DtmfExecutionService})
 * executes the interaction. FreeSWITCH specifics stay behind the media/event
 * boundary — no raw ESL commands reach campaign code.
 * <p>
 * All methods must be idempotent and must not throw: duplicate DTMF events
 * after a terminal result are no-ops, and failures are recorded through the
 * existing failure/classification paths rather than breaking event
 * processing. Optional bean — contexts without a campaign layer run without
 * it (mirrors {@link PlaybackTrigger}).
 */
public interface DtmfCollectorTrigger {

    /**
     * A DTMF digit was reported by the provider on an active call.
     *
     * @param callSessionId the call session receiving the digit
     * @param callAttemptId the campaign attempt (may be null)
     * @param digit         the reported digit (single character)
     */
    void onDtmfDigit(UUID callSessionId, UUID callAttemptId, String digit);

    /**
     * The DTMF collection window for a call expired. Implementations must
     * correlate this to the interaction created for this call — a stale
     * timeout from an earlier interaction must not terminate a later one.
     *
     * @param callSessionId the call session whose collection expired
     */
    void onDtmfTimeout(UUID callSessionId);
}
