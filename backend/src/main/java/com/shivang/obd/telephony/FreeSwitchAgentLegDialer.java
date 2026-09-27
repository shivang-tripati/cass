package com.shivang.obd.telephony;

import com.shivang.obd.voice.agent.AgentLegDialer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FreeSWITCH agent-leg originate (VB-3 CONNECT_BY_AGENT).
 * <p>
 * The originate command/UUID-extraction contract is the same as the
 * customer leg ({@code bgapi originate {origination_caller_id_number=X}
 * sofia/gateway/<gw>/<target>}) with one difference: the agent dial target
 * may be a SIP contact resolved by FreeSWITCH rather than an E.164 PSTN
 * number, so it is passed as the dial string directly. The returned channel
 * UUID becomes the agent leg's {@code provider_call_id}.
 */
@Component
@ConditionalOnProperty(prefix = "telephony.freeswitch", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class FreeSwitchAgentLegDialer implements AgentLegDialer {

    private final FreeSwitchProperties properties;

    @Override
    public String originateAgentLeg(String callerId, String dialTarget,
                                    String gatewayName, String profile) {
        if (dialTarget == null || dialTarget.isBlank()) {
            throw new EslException("Agent dial target is required");
        }
        String gateway = gatewayName != null && !gatewayName.isBlank()
                ? gatewayName : properties.getGateway();
        String effectiveProfile = profile != null && !profile.isBlank()
                ? profile : properties.getProfile();

        try (EslClient eslClient = new EslClient(properties)) {
            eslClient.connect();
            String uuid = eslClient.originate(callerId, dialTarget, gateway, effectiveProfile);
            log.info("Agent leg originated (uuid={}, gateway={})", maskUuid(uuid), gateway);
            return uuid;
        }
    }

    private String maskUuid(String uuid) {
        if (uuid == null || uuid.length() <= 8) {
            return uuid;
        }
        return uuid.substring(0, 8) + "***";
    }
}
