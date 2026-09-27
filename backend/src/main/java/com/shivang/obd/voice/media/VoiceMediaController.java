package com.shivang.obd.voice.media;

import java.util.UUID;

/**
 * Voice media boundary.
 * <p>
 * Controls media operations during a call (playback, DTMF collection, recording).
 * Implemented by the telephony adapter (FreeSWITCH).
 * <p>
 * Phase R: Placeholder interface. Actual implementation will be added
 * when PLAYFILE/DTMF workflows are implemented.
 */
public interface VoiceMediaController {

    /**
     * Plays audio to a call leg.
     *
     * @param callSessionId the call session
     * @param legId the call leg
     * @param audioUri the audio resource URI
     */
    void playAudio(UUID callSessionId, UUID legId, String audioUri);

    /**
     * Stops current playback on a call leg.
     *
     * @param callSessionId the call session
     * @param legId the call leg
     */
    void stopPlayback(UUID callSessionId, UUID legId);

    /**
     * Terminates an active call (VB-1 PLAYFILE teardown after playback
     * completion or unrecoverable playback failure).
     *
     * @param callSessionId the call session
     * @param providerCallId the provider channel id (preferred; may be null
     *                       to let the implementation resolve it from the
     *                       call session)
     */
    void terminateCall(UUID callSessionId, String providerCallId);

    /**
     * Collects DTMF digits from a call leg.
     *
     * @param callSessionId the call session
     * @param legId the call leg
     * @param maxDigits maximum digits to collect
     * @param terminator terminator key (e.g., '#')
     * @param timeoutSecs timeout in seconds
     * @return collected DTMF digits
     */
    String collectDtmf(UUID callSessionId, UUID legId, int maxDigits, String terminator, int timeoutSecs);

    /**
     * Bridges two call legs together.
     *
     * @param callSessionId the call session
     * @param legAId first leg
     * @param legBId second leg
     */
    void bridge(UUID callSessionId, UUID legAId, UUID legBId);

    /**
     * Starts recording a call leg.
     *
     * @param callSessionId the call session
     * @param legId the call leg
     * @param format recording format (wav, mp3, etc.)
     */
    void startRecording(UUID callSessionId, UUID legId, String format);

    /**
     * Stops recording a call leg.
     *
     * @param callSessionId the call session
     * @param legId the call leg
     */
    void stopRecording(UUID callSessionId, UUID legId);
}