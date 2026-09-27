package com.shivang.obd.telephony;

import com.shivang.obd.voice.media.VoiceMediaController;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * NO-OP media controller used when no real telephony provider is configured
 * ({@code telephony.freeswitch.enabled=false}, the default).
 * <p>
 * Mirrors {@code NoOpOutboundDialer}: operations that would reach FreeSWITCH
 * fail loudly instead of pretending media was played, so callers record a
 * real failure and the lifecycle (reservation release, attempt finalization)
 * stays correct in provider-less environments. {@code stopPlayback} is a
 * no-op, matching the VB-1 semantic in {@link FreeSwitchVoiceMediaController}.
 */
@Component
@ConditionalOnProperty(prefix = "telephony.freeswitch", name = "enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class NoOpVoiceMediaController implements VoiceMediaController {

    @Override
    public void playAudio(UUID callSessionId, UUID legId, String audioUri) {
        log.warn("NoOpVoiceMediaController.playAudio invoked — no real provider configured "
                + "(callSession={})", callSessionId);
        throw new com.shivang.obd.telephony.EslException("No telephony provider configured");
    }

    @Override
    public void stopPlayback(UUID callSessionId, UUID legId) {
        // VB-1: stopPlayback is a no-op in both implementations.
        log.debug("stopPlayback is a no-op in VB-1 (callSession={}, leg={})", callSessionId, legId);
    }

    @Override
    public void terminateCall(UUID callSessionId, String providerCallId) {
        // Nothing to terminate without a provider; treat as already terminated.
        log.debug("NoOpVoiceMediaController.terminateCall (callSession={})", callSessionId);
    }

    @Override
    public String collectDtmf(UUID callSessionId, UUID legId, int maxDigits, String terminator, int timeoutSecs) {
        throw new UnsupportedOperationException("DTMF collection is not part of VB-1 (PLAYFILE)");
    }

    @Override
    public void bridge(UUID callSessionId, UUID legAId, UUID legBId) {
        // No provider — fail loudly so callers record a real failure instead
        // of pretending a bridge exists (mirrors playAudio semantics).
        log.warn("NoOpVoiceMediaController.bridge invoked — no real provider configured "
                + "(callSession={})", callSessionId);
        throw new com.shivang.obd.telephony.EslException("No telephony provider configured");
    }

    @Override
    public void startRecording(UUID callSessionId, UUID legId, String format) {
        throw new UnsupportedOperationException("Recording is not part of VB-1 (PLAYFILE)");
    }

    @Override
    public void stopRecording(UUID callSessionId, UUID legId) {
        throw new UnsupportedOperationException("Recording is not part of VB-1 (PLAYFILE)");
    }
}
