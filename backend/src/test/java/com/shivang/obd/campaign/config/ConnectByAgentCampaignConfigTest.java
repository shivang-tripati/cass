package com.shivang.obd.campaign.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.CampaignType;
import com.shivang.obd.voice.agent.AgentRingWindow;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * VB-7A: the typed CONNECT_BY_AGENT configuration contract.
 *
 * <p>Covers what replaced the VB-6A placeholder (any non-empty object was
 * accepted), the bounds on every field, and — the reason the campaign type owns
 * its configuration — that agent configuration cannot leak into another type.
 */
class ConnectByAgentCampaignConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID QUEUE_ID =
            UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String validPayload() {
        return "{\"connectByAgent\": {"
                + "\"queueId\": \"" + QUEUE_ID + "\", "
                + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                + "\"ringDurationSeconds\": 60}}";
    }

    @Nested
    @DisplayName("A. typed configuration")
    class TypedConfiguration {

        @Test
        @DisplayName("A1. a complete configuration is accepted and round-trips unchanged")
        void validConfigurationRoundTrips() {
            var payload = json(validPayload());
            var config = (ConnectByAgentCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT, payload);

            assertThat(config.queueId()).isEqualTo(QUEUE_ID);
            assertThat(config.selectionStrategy())
                    .isEqualTo(AgentSelectionStrategy.LEAST_ACTIVE_RESERVATIONS);
            assertThat(config.ringDurationSeconds()).isEqualTo(60);
            assertThat(config.campaignType()).isEqualTo(CampaignType.CONNECT_BY_AGENT);
            assertThat(config.schemaVersion()).isEqualTo(ConfigSchemaVersion.V1);
            assertThat(config.toJson()).isEqualTo(payload);
        }

        @Test
        @DisplayName("A2. re-parsing the round-trip yields an equal config (stable codec)")
        void roundTripIsStable() {
            var config = (ConnectByAgentCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT, json(validPayload()));
            var reparsed = (ConnectByAgentCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT, config.toJson());
            assertThat(reparsed).isEqualTo(config);
        }

        @Test
        @DisplayName("A2b. there is exactly one implemented selection strategy")
        void onlyOneStrategyExists() {
            assertThat(AgentSelectionStrategy.values())
                    .containsExactly(AgentSelectionStrategy.LEAST_ACTIVE_RESERVATIONS);
            assertThat(AgentSelectionStrategy.LEAST_ACTIVE_RESERVATIONS.isImplemented()).isTrue();
        }

        @Test
        @DisplayName("A3. a missing queue reference is rejected")
        void missingQueueRejected() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT,
                    json("{\"connectByAgent\": {\"selectionStrategy\": "
                            + "\"LEAST_ACTIVE_RESERVATIONS\", \"ringDurationSeconds\": 60}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("queueId");

            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT,
                    json("{\"connectByAgent\": {\"queueId\": null, "
                            + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                            + "\"ringDurationSeconds\": 60}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("A3b. a non-UUID or blank queue reference is rejected")
        void malformedQueueRejected() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT,
                    json("{\"connectByAgent\": {\"queueId\": \"not-a-uuid\", "
                            + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                            + "\"ringDurationSeconds\": 60}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("queueId");
        }

        @Test
        @DisplayName("A4. an unsupported selection strategy is rejected")
        void invalidStrategyRejected() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT,
                    json("{\"connectByAgent\": {\"queueId\": \"" + QUEUE_ID + "\", "
                            + "\"selectionStrategy\": \"ROUND_ROBIN\", "
                            + "\"ringDurationSeconds\": 60}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("ROUND_ROBIN")
                    .hasMessageContaining("LEAST_ACTIVE_RESERVATIONS");

            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT,
                    json("{\"connectByAgent\": {\"queueId\": \"" + QUEUE_ID + "\", "
                            + "\"ringDurationSeconds\": 60}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("selectionStrategy");
        }

        @Test
        @DisplayName("A5. a ring duration outside the supported range is rejected")
        void invalidRingDurationRejected() {
            for (int seconds : new int[] {0, 1, 9, 241, 600, -30}) {
                assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                        CampaignType.CONNECT_BY_AGENT,
                        json("{\"connectByAgent\": {\"queueId\": \"" + QUEUE_ID + "\", "
                                + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                                + "\"ringDurationSeconds\": " + seconds + "}}")))
                        .as("ringDurationSeconds=" + seconds)
                        .isInstanceOf(CampaignConfigInvalidException.class)
                        .hasMessageContaining("ringDurationSeconds");
            }
        }

        @Test
        @DisplayName("A5b. the ring duration bounds are the shared authority's bounds")
        void ringBoundsComeFromTheAuthority() {
            assertThat(AgentRingWindow.MIN_RING_SECONDS).isEqualTo(10);
            assertThat(AgentRingWindow.MAX_RING_SECONDS).isEqualTo(240);
            assertThat(AgentRingWindow.DEFAULT_RING_SECONDS).isEqualTo(60);

            String atMin = "{\"connectByAgent\": {\"queueId\": \"" + QUEUE_ID + "\", "
                    + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                    + "\"ringDurationSeconds\": " + AgentRingWindow.MIN_RING_SECONDS + "}}";
            String atMax = "{\"connectByAgent\": {\"queueId\": \"" + QUEUE_ID + "\", "
                    + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                    + "\"ringDurationSeconds\": " + AgentRingWindow.MAX_RING_SECONDS + "}}";
            assertThat(CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT, json(atMin))).isNotNull();
            assertThat(CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT, json(atMax))).isNotNull();
        }

        @Test
        @DisplayName("A5c. a non-integer ring duration is rejected")
        void nonIntegerRingDurationRejected() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT,
                    json("{\"connectByAgent\": {\"queueId\": \"" + QUEUE_ID + "\", "
                            + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                            + "\"ringDurationSeconds\": \"60\"}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("A6. an unsupported field is rejected rather than silently ignored")
        void unknownFieldRejected() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT,
                    json("{\"connectByAgent\": {\"queueId\": \"" + QUEUE_ID + "\", "
                            + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                            + "\"ringDurationSeconds\": 60, \"agentIds\": [\"x\"]}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("agentIds");
        }

        @Test
        @DisplayName("A6b. a non-object connectByAgent entry is rejected")
        void nonObjectEntryRejected() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.CONNECT_BY_AGENT, json("{\"connectByAgent\": 7}")))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("A7. the effective ring window falls back to the platform default")
        void effectiveRingSecondsFallsBack() {
            assertThat(AgentRingWindow.effectiveSeconds(null))
                    .isEqualTo(AgentRingWindow.DEFAULT_RING_SECONDS);
            assertThat(AgentRingWindow.effectiveSeconds(5))
                    .isEqualTo(AgentRingWindow.DEFAULT_RING_SECONDS);
            assertThat(AgentRingWindow.effectiveSeconds(1000))
                    .isEqualTo(AgentRingWindow.DEFAULT_RING_SECONDS);
            assertThat(AgentRingWindow.effectiveSeconds(30)).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("A8. CONNECT_BY_AGENT-only enforcement")
    class CampaignTypeSafety {

        @Test
        @DisplayName("A8a. a PLAYFILE payload cannot carry agent configuration")
        void playfileCannotCarryAgentConfiguration() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.PLAYFILE, json(validPayload())))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("A8b. a DTMF payload cannot carry agent configuration")
        void dtmfCannotCarryAgentConfiguration() {
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.DTMF, json(validPayload())))
                    .isInstanceOf(CampaignConfigInvalidException.class);

            // and neither DTMF shape is reachable through an agent payload
            assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                    CampaignType.DTMF, json("{\"connectByAgent\": {}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("A8c. an IVR-bearing DTMF payload still parses as IVR, not as agent config")
        void ivrDtmfShapeIsUnaffected() {
            var config = CampaignTypeConfig.fromTypeConfig(CampaignType.DTMF,
                    json("{\"ivr\": {\"treeId\": \"" + QUEUE_ID + "\"}}"));
            assertThat(config).isInstanceOf(IvrCampaignConfig.class);
        }

        @Test
        @DisplayName("A8d. a DTMF campaign with a CONNECT_BY_AGENT action is still DTMF")
        void dtmfAgentActionIsNotACampaignType() {
            // The input-driven action is a property of the interaction, not of
            // the campaign type: it must keep parsing as a DTMF configuration so
            // VB-7A does not repurpose it.
            var config = CampaignTypeConfig.fromTypeConfig(CampaignType.DTMF,
                    json("{\"dtmf\": {\"expected\": \"1\", \"action\": \"CONNECT_BY_AGENT\"}}"));
            assertThat(config).isInstanceOf(DtmfCampaignConfig.class);
            assertThat(((DtmfCampaignConfig) config).action()).isEqualTo("CONNECT_BY_AGENT");
        }
    }
}
