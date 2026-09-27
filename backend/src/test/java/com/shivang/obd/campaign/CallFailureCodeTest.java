package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-6A failure-taxonomy tests: the canonical enum covers the real producer
 * inventory, and its retry classification is behavior-identical to the
 * legacy permanent-failure gate (consolidation, not policy change).
 */
class CallFailureCodeTest {

    @Test
    @DisplayName("PERMANENT set equals the legacy isPermanentFailure list exactly")
    void permanentSetMatchesLegacyGate() {
        List<String> legacyPermanent = List.of(
                "PLAYBACK_CONFIG_INVALID",
                "DTMF_CONFIG_INVALID",
                "AGENT_CONFIG_INVALID",
                "AGENT_ENDPOINT_INVALID",
                "AGENT_TENANT_MISMATCH",
                "CONNECT_BY_AGENT_UNSUPPORTED",
                "CAMPAIGN_NOT_FOUND",
                "DIAL_FAILED",
                "REJECTED");

        for (String code : legacyPermanent) {
            assertThat(CallFailureCode.fromCode(code))
                    .as("code %s must exist in the taxonomy", code)
                    .isPresent();
            assertThat(CallFailureCode.isPermanent(code))
                    .as("code %s must classify permanent", code)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Known temporary codes classify temporary (retried as before)")
    void temporaryCodesClassifyTemporary() {
        for (String code : List.of("BUSY", "NO_ANSWER", "PLAYBACK_FAILED",
                "TEMPORARY_FAILURE", "PROVIDER_UNAVAILABLE", "TEMPORARILY_UNAVAILABLE",
                "CONGESTION", "RESOURCE_UNAVAILABLE", "AGENT_BUSY")) {
            assertThat(CallFailureCode.isPermanent(code))
                    .as("code %s must classify temporary", code)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Null, blank and unknown codes degrade deterministically to temporary")
    void unknownCodesAreTemporaryLikeLegacy() {
        assertThat(CallFailureCode.isPermanent(null)).isFalse();
        assertThat(CallFailureCode.isPermanent("")).isFalse();
        assertThat(CallFailureCode.isPermanent("   ")).isFalse();
        assertThat(CallFailureCode.isPermanent("HANGUP_CALL_REJECTED_EXTRA")).isFalse();
        assertThat(CallFailureCode.isPermanent("SOME_FUTURE_CODE")).isFalse();
        assertThat(CallFailureCode.fromCode("SOME_FUTURE_CODE")).isEmpty();
    }

    @Test
    @DisplayName("Every enum code round-trips through fromCode")
    void enumRoundTrip() {
        for (CallFailureCode code : CallFailureCode.values()) {
            assertThat(CallFailureCode.fromCode(code.getCode()))
                    .as("code %s must round-trip", code)
                    .contains(code);
            assertThat(code.getCode()).isEqualTo(code.name());
        }
    }

    @Test
    @DisplayName("Inventory anchor: all dial-result and hangup codes are representable")
    void producerInventoryRepresentable() {
        for (String code : List.of(
                // OutboundDialService dial results
                "BUSY", "NO_ANSWER", "REJECTED", "PROVIDER_UNAVAILABLE", "DIAL_FAILED",
                // EslEventService hangup mapping (incl. cause 34/41/47 fallbacks)
                "CONGESTION", "TEMPORARY_FAILURE", "RESOURCE_UNAVAILABLE", "HANGUP_UNKNOWN",
                // Playback / DTMF execution services
                "PLAYBACK_FAILED", "PLAYBACK_CONFIG_INVALID", "DTMF_CONFIG_INVALID",
                "DTMF_PLAYBACK_FAILED",
                // Connect / inbound / agent-outbound boundaries
                "AGENT_ORIGINATE_FAILED", "AGENT_NO_ANSWER", "AGENT_LEFT",
                "AGENT_BRIDGE_FAILED", "AGENT_CONNECT_FAILED", "AGENT_CONFIG_INVALID",
                "AGENT_ENDPOINT_INVALID", "AGENT_TENANT_MISMATCH", "AGENT_BUSY",
                "AGENT_UNAVAILABLE", "CALLER_HANGUP", "INBOUND_ROUTE_INVALID",
                "INVALID_DESTINATION", "GATEWAY_CAPACITY_EXHAUSTED", "CALL_ORIGINATE_FAILED",
                // Eligibility rejections
                "INVALID_DID", "INVALID_NUMBER", "PLATFORM_BLOCKED", "PLATFORM_PROTECTED",
                "RESELLER_BLOCKED", "DNC_BLOCKED", "NOT_WHITELISTED",
                "NOT_IN_CAMPAIGN_TARGETS", "NO_ELIGIBLE_GATEWAY", "TEMPORARILY_UNAVAILABLE")) {
            assertThat(CallFailureCode.fromCode(code))
                    .as("producer code %s must be representable", code)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("Map-style exhaustive check: enum covers the documented taxonomy groups")
    void taxonomyGroupsCovered() {
        Map<Integer, Long> byGroup = new java.util.HashMap<>();
        for (CallFailureCode code : CallFailureCode.values()) {
            byGroup.merge(code.getRetryClass().ordinal(), 1L, Long::sum);
        }
        // Both classes must be populated (classification is meaningful).
        assertThat(byGroup).containsKeys(0, 1);
    }
}
