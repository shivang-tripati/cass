package com.shivang.obd.voice.outbound;

import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.api.error.CommonErrorCode;

/**
 * Deterministic rejection of an agent outbound call request. Carries a
 * machine-readable reason code from the shared vocabularies
 * ({@code AgentReasons}/{@code AgentFoundationReasons}/{@code AgentOutboundReasons})
 * so the API layer can surface explainable failures without leaking
 * internals.
 */
public class AgentOutboundCallException extends BusinessException {

    private final String reasonCode;

    public AgentOutboundCallException(String reasonCode, String message) {
        super(CommonErrorCode.BUSINESS_RULE_VIOLATION, message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
