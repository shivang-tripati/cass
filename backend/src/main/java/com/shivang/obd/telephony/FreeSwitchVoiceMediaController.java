package com.shivang.obd.telephony;

import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FreeSWITCH implementation of the {@link VoiceMediaController} media
 * boundary (VB-1 PLAYFILE).
 * <p>
 * Translates business media operations into FreeSWITCH ESL commands:
 * <ul>
 *   <li>{@link #playAudio} → {@code uuid_broadcast <uuid> <path> aleg}</li>
 *   <li>{@link #terminateCall} → {@code uuid_kill <uuid> NORMAL_CLEARING}</li>
 *   <li>{@link #stopPlayback} → intentional no-op in VB-1: a remote hangup
 *       kills the channel (terminating playback natively), and
 *       post-completion teardown hangs the channel up. A dedicated mid-play
 *       interrupt is unnecessary until a flow requires it.</li>
 * </ul>
 * <p>
 * The FreeSWITCH channel UUID is resolved from
 * {@link CallSession#getProviderCallId()} — the UUID returned by originate is
 * the channel identity for all in-call media operations. Raw ESL commands
 * never leak into campaign code; they stay behind this boundary. Active only
 * when {@code telephony.freeswitch.enabled=true}, mirroring
 * {@link FreeSwitchOutboundDialer}.
 */
@Component
@ConditionalOnProperty(prefix = "telephony.freeswitch", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class FreeSwitchVoiceMediaController implements VoiceMediaController {

    private final FreeSwitchProperties properties;
    private final CallSessionRepository callSessionRepository;
    private final CallLegRepository callLegRepository;

    @Override
    public void playAudio(UUID callSessionId, UUID legId, String audioUri) {
        if (Objects.isNull(audioUri) || audioUri.isBlank()) {
            throw new IllegalArgumentException("Audio URI is required for playback");
        }
        String channelUuid = resolveChannelUuid(callSessionId, "playAudio");

        try (EslClient eslClient = new EslClient(properties)) {
            eslClient.connect();
            eslClient.playFile(channelUuid, audioUri);
            log.info("Playback requested on channel {} (callSession={}, leg={})",
                    maskUuid(channelUuid), callSessionId, legId);
        } catch (EslException e) {
            log.warn("Playback command failed for callSession {}: {}", callSessionId, e.getMessage());
            throw e;
        }
    }

    @Override
    public void stopPlayback(UUID callSessionId, UUID legId) {
        log.debug("stopPlayback is a no-op in VB-1 (callSession={}, leg={})", callSessionId, legId);
    }

    @Override
    public void terminateCall(UUID callSessionId, String providerCallId) {
        String channelUuid = providerCallId != null && !providerCallId.isBlank()
                ? providerCallId
                : resolveChannelUuid(callSessionId, "terminateCall");

        try (EslClient eslClient = new EslClient(properties)) {
            eslClient.connect();
            eslClient.hangup(channelUuid);
            log.info("Hangup requested on channel {} (callSession={})",
                    maskUuid(channelUuid), callSessionId);
        } catch (EslException e) {
            log.warn("Hangup command failed for callSession {}: {}", callSessionId, e.getMessage());
            throw e;
        }
    }

    @Override
    public String collectDtmf(UUID callSessionId, UUID legId, int maxDigits, String terminator, int timeoutSecs) {
        throw new UnsupportedOperationException("DTMF collection is not part of VB-1 (PLAYFILE)");
    }

    /**
     * Bridges two legs of a call session (VB-3 CONNECT_BY_AGENT).
     * <p>
     * Issues {@code uuid_bridge <caller> <agent>} using each leg's stored
     * provider call id (the originate-returned FreeSWITCH UUID). Command
     * acceptance is NOT bridge confirmation — the authoritative confirmation
     * is the CHANNEL_BRIDGE event handled by {@code EslEventService}.
     * The caller leg (legA) is the bridge anchor.
     */
    @Override
    public void bridge(UUID callSessionId, UUID legAId, UUID legBId) {
        CallLeg legA = callLegRepository.findByIdAndDeletedAtIsNull(legAId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Bridge leg A not found: " + legAId));
        CallLeg legB = callLegRepository.findByIdAndDeletedAtIsNull(legBId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Bridge leg B not found: " + legBId));
        if (!legA.getCallSessionId().equals(callSessionId)
                || !legB.getCallSessionId().equals(callSessionId)) {
            throw new IllegalArgumentException("Bridge legs do not belong to call session " + callSessionId);
        }
        String callerUuid = legA.getProviderCallId();
        String agentUuid = legB.getProviderCallId();
        if (callerUuid == null || callerUuid.isBlank() || agentUuid == null || agentUuid.isBlank()) {
            throw new IllegalStateException(
                "Bridge requires provider call ids on both legs (callSession=" + callSessionId + ")");
        }

        try (EslClient eslClient = new EslClient(properties)) {
            eslClient.connect();
            eslClient.bridge(callerUuid, agentUuid);
            log.info("Bridge requested (callSession={}, caller={}, agent={})",
                    callSessionId, maskUuid(callerUuid), maskUuid(agentUuid));
        } catch (EslException e) {
            log.warn("Bridge command failed for callSession {}: {}", callSessionId, e.getMessage());
            throw e;
        }
    }

    @Override
    public void startRecording(UUID callSessionId, UUID legId, String format) {
        throw new UnsupportedOperationException("Recording is not part of VB-1 (PLAYFILE)");
    }

    @Override
    public void stopRecording(UUID callSessionId, UUID legId) {
        throw new UnsupportedOperationException("Recording is not part of VB-1 (PLAYFILE)");
    }

    /**
     * Resolves the FreeSWITCH channel UUID for a call session from its stored
     * provider call id (the originate-returned UUID).
     */
    private String resolveChannelUuid(UUID callSessionId, String operation) {
        if (callSessionId == null) {
            throw new IllegalArgumentException("callSessionId is required for " + operation);
        }
        return callSessionRepository.findById(callSessionId)
                .map(CallSession::getProviderCallId)
                .filter(id -> !id.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "No provider call id on call session " + callSessionId + " for " + operation));
    }

    private String maskUuid(String uuid) {
        if (uuid == null || uuid.length() <= 8) {
            return uuid;
        }
        return uuid.substring(0, 8) + "***";
    }
}
