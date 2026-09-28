package com.shivang.obd.campaign.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.CampaignType;
import com.shivang.obd.voice.agent.AgentRingWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * VB-7B: the typed MISSED_CALL configuration contract, and the campaign-type
 * isolation that makes it impossible for one type's configuration to reach
 * another.
 */
class MissedCallCampaignConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String valid(int seconds) {
        return "{\"missedCall\": {\"ringDurationSeconds\": " + seconds + "}}";
    }

    @Nested
    @DisplayName("A. configuration")
    class Configuration {

        @Test
        @DisplayName("A1. a valid configuration is accepted and round-trips unchanged")
        void validConfigurationRoundTrips() {
            var payload = json(valid(30));
            var config = (MissedCallCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                    CampaignType.MISSED_CALL, payload);

            assertThat(config.ringDurationSeconds()).isEqualTo(30);
            assertThat(config.effectiveRingSeconds()).isEqualTo(30);
            assertThat(config.campaignType()).isEqualTo(CampaignType.MISSED_CALL);
            assertThat(config.schemaVersion()).isEqualTo(ConfigSchemaVersion.V1);
            assertThat(config.toJson()).isEqualTo(payload);
        }

        @Test
        @DisplayName("A2. the round-trip is stable")
        void roundTripIsStable() {
            var config = (MissedCallCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                    CampaignType.MISSED_CALL, json(valid(45)));
            var reparsed = (MissedCallCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                    CampaignType.MISSED_CALL, config.toJson());
            assertThat(reparsed).isEqualTo(config);
        }

        @Test
        @DisplayName("A3. a missing missedCall root is rejected")
        void missingRootRejected() {
            for (String raw : new String[] {null, "{}", "{\"dtmf\":{}}", "null"}) {
                assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                        CampaignType.MISSED_CALL,
                        raw == null ? null : json(raw)))
                        .as("payload=%s", raw)
                        .isInstanceOf(CampaignConfigInvalidException.class);
            }
        }

        @Test
        @DisplayName("A4. a missing ring duration is rejected (the value is required)")
        void missingRingDurationRejected() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.MISSED_CALL, json("{\"missedCall\": {}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("ringDurationSeconds");
        }

        @Test
        @DisplayName("A5. a value below 10 is rejected")
        void belowMinimumRejected() {
            for (int seconds : new int[] {0, 1, 9, -1, -30}) {
                assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                        CampaignType.MISSED_CALL, json(valid(seconds))))
                        .as("ringDurationSeconds=%s", seconds)
                        .isInstanceOf(CampaignConfigInvalidException.class)
                        .hasMessageContaining("ringDurationSeconds");
            }
        }

        @Test
        @DisplayName("A6. a value above 60 is rejected")
        void aboveMaximumRejected() {
            for (int seconds : new int[] {61, 120, 300, 3600}) {
                assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                        CampaignType.MISSED_CALL, json(valid(seconds))))
                        .as("ringDurationSeconds=%s", seconds)
                        .isInstanceOf(CampaignConfigInvalidException.class)
                        .hasMessageContaining("ringDurationSeconds");
            }
        }

        @Test
        @DisplayName("A7. a non-integer ring duration is rejected")
        void nonIntegerRejected() {
            for (String raw : new String[] {"\"30\"", "30.5", "true"}) {
                assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                        CampaignType.MISSED_CALL,
                        json("{\"missedCall\": {\"ringDurationSeconds\": " + raw + "}}")))
                        .as("ringDurationSeconds=%s", raw)
                        .isInstanceOf(CampaignConfigInvalidException.class);
            }
        }

        @Test
        @DisplayName("A8. an unknown field is rejected rather than silently ignored")
        void unknownFieldRejected() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.MISSED_CALL,
                    json("{\"missedCall\": {\"ringDurationSeconds\": 30, "
                            + "\"queueId\": \"x\"}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("queueId");
        }

        @Test
        @DisplayName("A9/A10. both bounds are accepted")
        void boundsAccepted() {
            assertThat(MissedCallRingWindow.MIN_RING_SECONDS).isEqualTo(10);
            assertThat(MissedCallRingWindow.MAX_RING_SECONDS).isEqualTo(60);
            assertThat(MissedCallRingWindow.DEFAULT_RING_SECONDS).isEqualTo(30);

            assertThat(CampaignTypeConfig.fromTypeConfig(
                    CampaignType.MISSED_CALL, json(valid(10)))).isNotNull();
            assertThat(CampaignTypeConfig.fromTypeConfig(
                    CampaignType.MISSED_CALL, json(valid(60)))).isNotNull();
        }

        @Test
        @DisplayName("A10b. the maximum stays well under the stale-recovery threshold, so the "
                + "MISSED_CALL policy always decides the outcome before recovery does")
        void maximumIsUnderTheStaleThreshold() {
            // StaleCallReconciler reconciles stranded attempts at 5 minutes and
            // records a FAILURE. A budget above that would let recovery win, and
            // a successful ring would be recorded as a retryable failure.
            assertThat(MissedCallRingWindow.MAX_RING_SECONDS).isLessThan(300);
        }

        @Test
        @DisplayName("A10c. the MISSED_CALL budget is independent of the agent ring window")
        void boundsAreNotSharedWithTheAgentWindow() {
            // Two different missions bounded by two different authorities; sharing
            // a constant would silently couple an outbound ring to an agent leg.
            assertThat(MissedCallRingWindow.MAX_RING_SECONDS)
                    .isNotEqualTo(AgentRingWindow.MAX_RING_SECONDS);
        }

        @Test
        @DisplayName("A10d. an unresolvable value degrades to the default, never to zero")
        void effectiveSecondsFallsBack() {
            assertThat(MissedCallRingWindow.effectiveSeconds(null)).isEqualTo(30);
            assertThat(MissedCallRingWindow.effectiveSeconds(5)).isEqualTo(30);
            assertThat(MissedCallRingWindow.effectiveSeconds(600)).isEqualTo(30);
            assertThat(MissedCallRingWindow.effectiveSeconds(15)).isEqualTo(15);
        }
    }

    @Nested
    @DisplayName("B. type isolation")
    class TypeIsolation {

        private final String cba =
                "{\"connectByAgent\": {\"queueId\": \"3f2504e0-4f89-11d3-9a0c-0305e82c3301\", "
                        + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                        + "\"ringDurationSeconds\": 60}}";

        @Test
        @DisplayName("B11. MISSED_CALL cannot carry PLAYFILE content configuration")
        void cannotCarryPlayfile() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.MISSED_CALL, json("{\"playfile\": {}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("B12. MISSED_CALL cannot carry DTMF configuration")
        void cannotCarryDtmf() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.MISSED_CALL,
                    json("{\"dtmf\": {\"expected\": \"1\"}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("B13. MISSED_CALL cannot carry an IVR tree")
        void cannotCarryIvr() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.MISSED_CALL,
                    json("{\"ivr\": {\"treeId\": \"3f2504e0-4f89-11d3-9a0c-0305e82c3301\"}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("B14. MISSED_CALL cannot carry CONNECT_BY_AGENT configuration")
        void cannotCarryConnectByAgent() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.MISSED_CALL, json(cba)))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("B15. PLAYFILE cannot carry a missedCall payload (it accepts only the "
                + "empty configuration)")
        void playfileCannotCarryMissedCall() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.PLAYFILE, json(valid(30))))
                    .isInstanceOf(CampaignConfigInvalidException.class);
            // and PLAYFILE's own valid shape is still accepted
            assertThat(CampaignTypeConfig.fromTypeConfig(CampaignType.PLAYFILE, null))
                    .isNotNull();
        }

        @Test
        @DisplayName("B15b. DTMF cannot carry a missedCall payload")
        void dtmfCannotCarryMissedCall() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.DTMF, json(valid(30))))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("B15c. CONNECT_BY_AGENT cannot carry a missedCall payload")
        void connectByAgentCannotCarryMissedCall() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT, json(valid(30))))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("B16. a DTMF campaign whose terminal action is CONNECT_BY_AGENT is still "
                + "DTMF - the input-driven action is unchanged")
        void dtmfAgentActionIsNotACampaignType() {
            var config = CampaignTypeConfig.fromTypeConfig(CampaignType.DTMF,
                    json("{\"dtmf\": {\"expected\": \"1\", \"action\": \"CONNECT_BY_AGENT\"}}"));
            assertThat(config).isInstanceOf(DtmfCampaignConfig.class);
            assertThat(((DtmfCampaignConfig) config).action()).isEqualTo("CONNECT_BY_AGENT");
        }

        @Test
        @DisplayName("B16b. dispatch resolves each of the four types independently")
        void dispatchResolvesEveryType() {
            assertThat(CampaignTypeConfig.fromTypeConfig(CampaignType.PLAYFILE, null))
                    .isInstanceOf(PlayfileCampaignConfig.class);
            assertThat(CampaignTypeConfig.fromTypeConfig(CampaignType.DTMF,
                    json("{\"dtmf\": {\"expected\": \"1\"}}")))
                    .isInstanceOf(DtmfCampaignConfig.class);
            assertThat(CampaignTypeConfig.fromTypeConfig(CampaignType.DTMF,
                    json("{\"ivr\": {\"treeId\": \"3f2504e0-4f89-11d3-9a0c-0305e82c3301\"}}")))
                    .isInstanceOf(IvrCampaignConfig.class);
            assertThat(CampaignTypeConfig.fromTypeConfig(CampaignType.CONNECT_BY_AGENT,
                    json(cba))).isInstanceOf(ConnectByAgentCampaignConfig.class);
            assertThat(CampaignTypeConfig.fromTypeConfig(CampaignType.MISSED_CALL,
                    json(valid(30)))).isInstanceOf(MissedCallCampaignConfig.class);
        }
    }
}
