package com.shivang.obd.voice.agent;

/**
 * Provider-agnostic agent-leg originate boundary (VB-3).
 * <p>
 * Reuses the exact originate mechanism the existing
 * {@code FreeSwitchOutboundDialer} uses (bgapi originate via the shared
 * {@code EslClient}) — it is not a second originate implementation, only a
 * focused seam so agent connection code never touches ESL directly.
 * Implementations live in the telephony layer, mirroring
 * {@code OutboundDialer}/{@code VoiceMediaController}.
 */
public interface AgentLegDialer {

    /**
     * Places the agent-leg call.
     *
     * @param callerId   the DID/E.164 number used as caller id (the
     *                   campaign DID — the agent should see the same CLI)
     * @param dialTarget the endpoint dial string (SIP URI / E.164)
     * @param gatewayName the FreeSWITCH gateway to originate through
     * @param profile     the FreeSWITCH sofia profile
     * @return the FreeSWITCH channel UUID for the new agent leg
     * @throws com.shivang.obd.telephony.EslException if originate fails
     */
    String originateAgentLeg(String callerId, String dialTarget,
                             String gatewayName, String profile);
}
