package com.shivang.obd.voice.media;

/**
 * Boundary for triggering PLAYFILE playback after an answer event.
 * <p>
 * Implemented by the campaign execution layer (which owns the campaign /
 * audio-asset context and the PLAYFILE policy). The telephony event service
 * calls this on CHANNEL_ANSWER for campaign calls; the implementation decides
 * whether the call actually requires playback (PLAYFILE + AUDIO campaigns)
 * and which asset to play. Implemented as an optional Spring bean so tests
 * and non-campaign contexts can run without it.
 */
public interface PlaybackTrigger {

    /**
     * Called when a campaign call was answered and media may now start.
     * Implementations must be idempotent and must not throw — playback
     * failures are handled through the ESL playback events.
     *
     * @param callSessionId the answered call session
     * @param callAttemptId the campaign attempt (null if the session is not
     *                      campaign-linked)
     */
    void onAnswered(java.util.UUID callSessionId, java.util.UUID callAttemptId);

    /**
     * Called when playback of the campaign content has completed — the
     * authoritative PLAYBACK_STOP event, not command acceptance.
     * Implementations act only for campaigns whose type they own (PLAYFILE →
     * teardown; DTMF → begin collection) and no-op otherwise, so multiple
     * trigger beans coexist without dispatch ambiguity. Must be idempotent
     * and must not throw.
     *
     * @param callSessionId the call session whose playback completed
     * @param callAttemptId the campaign attempt (null if not campaign-linked)
     */
    default void onPlaybackCompleted(java.util.UUID callSessionId, java.util.UUID callAttemptId) {
    }
}
