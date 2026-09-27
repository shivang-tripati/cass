package com.shivang.obd.telephony;

import com.shivang.obd.voice.agent.AgentLegDialer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * NO-OP agent-leg dialer used when no real provider is configured —
 * mirrors {@code NoOpOutboundDialer}/{@code NoOpVoiceMediaController}: it
 * fails loudly instead of pretending an agent leg was originated, so
 * CONNECT_BY_AGENT records a real failure and never creates false-positive
 * CONNECTED/BRIDGED states in provider-less environments.
 */
@Component
@ConditionalOnProperty(prefix = "telephony.freeswitch", name = "enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class NoOpAgentLegDialer implements AgentLegDialer {

    @Override
    public String originateAgentLeg(String callerId, String dialTarget,
                                    String gatewayName, String profile) {
        log.warn("NoOpAgentLegDialer invoked — no real provider configured "
                + "(target={})", mask(dialTarget));
        throw new EslException("No telephony provider configured");
    }

    private String mask(String target) {
        if (target == null || target.length() <= 4) {
            return target;
        }
        return "***" + target.substring(target.length() - 4);
    }
}
