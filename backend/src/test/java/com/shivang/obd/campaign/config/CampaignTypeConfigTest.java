package com.shivang.obd.campaign.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.CampaignType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * VB-6A typed campaign configuration codec tests: strict parsing per type,
 * backward-compatible JSON contracts, deterministic rejection of invalid
 * payloads.
 */
class CampaignTypeConfigTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonNode json(String raw) {
        return MAPPER.readTree(raw);
    }

    // === PLAYFILE ===

    @Test
    @DisplayName("PLAYFILE: absent, null and empty-object typeConfig are all valid")
    void playfileToleratesAbsentPayload() {
        assertThat(CampaignTypeConfig.fromTypeConfig(CampaignType.PLAYFILE, null))
                .isInstanceOf(PlayfileCampaignConfig.class);
        assertThat(CampaignTypeConfig.fromTypeConfig(CampaignType.PLAYFILE, json("null")))
                .isInstanceOf(PlayfileCampaignConfig.class);
        assertThat(CampaignTypeConfig.fromTypeConfig(CampaignType.PLAYFILE, json("{}")))
                .isInstanceOf(PlayfileCampaignConfig.class);
    }

    @Test
    @DisplayName("PLAYFILE: non-empty payload is rejected deterministically")
    void playfileRejectsPayload() {
        assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                CampaignType.PLAYFILE, json("{\"unknown\": true}")))
                .isInstanceOf(CampaignConfigInvalidException.class)
                .hasMessageContaining("PLAYFILE");
    }

    @Test
    @DisplayName("PLAYFILE: canonical JSON round-trips to an empty object")
    void playfileRoundTrip() {
        var config = (PlayfileCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                CampaignType.PLAYFILE, null);
        assertThat(config.toJson().isEmpty()).isTrue();
        assertThat(config.schemaVersion()).isEqualTo(ConfigSchemaVersion.V1);
    }

    // === DTMF (compatibility with existing persisted JSON) ===

    @Test
    @DisplayName("DTMF: existing persisted shape parses unchanged")
    void dtmfParsesExistingShape() {
        var config = (DtmfCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                CampaignType.DTMF,
                json("{\"dtmf\": {\"expected\": \"1\", \"timeoutSecs\": 15}}"));
        assertThat(config.expected()).isEqualTo("1");
        assertThat(config.timeoutSecs()).isEqualTo(15);
        assertThat(config.terminator()).isNull();
        assertThat(config.action()).isEqualTo("TERMINATE");
    }

    @Test
    @DisplayName("DTMF: canonical JSON round-trips to the persisted shape")
    void dtmfRoundTrip() {
        var config = (DtmfCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                CampaignType.DTMF,
                json("{\"dtmf\": {\"expected\": \"12\", \"terminator\": \"#\", "
                        + "\"maxDigits\": 4, \"timeoutSecs\": 20, \"action\": \"CONNECT_BY_AGENT\"}}"));
        JsonNode roundTrip = config.toJson();
        assertThat(roundTrip.get("dtmf").get("expected").asText()).isEqualTo("12");
        assertThat(roundTrip.get("dtmf").get("terminator").asText()).isEqualTo("#");
        assertThat(roundTrip.get("dtmf").get("maxDigits").asInt()).isEqualTo(4);
        assertThat(roundTrip.get("dtmf").get("timeoutSecs").asInt()).isEqualTo(20);
        assertThat(roundTrip.get("dtmf").get("action").asText()).isEqualTo("CONNECT_BY_AGENT");
        // Reparsing the round-trip yields the same config (stable codec).
        var reparsed = DtmfCampaignConfig.fromTypeConfig(roundTrip);
        assertThat(reparsed).isEqualTo(config);
    }

    @Test
    @DisplayName("DTMF: invalid payloads keep the legacy strict rejection")
    void dtmfRejectsInvalid() {
        assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                CampaignType.DTMF, json("{\"dtmf\": {\"expected\": \"abc\"}}")))
                .isInstanceOf(CampaignConfigInvalidException.class);
        assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                CampaignType.DTMF, json("{}")))
                .isInstanceOf(CampaignConfigInvalidException.class);
        assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                CampaignType.DTMF, null))
                .isInstanceOf(CampaignConfigInvalidException.class);
    }

    // === CONNECT_BY_AGENT ===

    @Test
    @DisplayName("CONNECT_BY_AGENT: non-empty legacy payload accepted and round-trips untouched")
    void connectAcceptsLegacyPayload() {
        var payload = json("{\"legacy\": {\"anything\": true}}");
        var config = (ConnectByAgentCampaignConfig) CampaignTypeConfig.fromTypeConfig(
                CampaignType.CONNECT_BY_AGENT, payload);
        assertThat(config.toJson().equals(payload)).isTrue();
    }

    @Test
    @DisplayName("CONNECT_BY_AGENT: absent or empty payload is rejected (existing write contract)")
    void connectRejectsAbsentPayload() {
        assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                CampaignType.CONNECT_BY_AGENT, null))
                .isInstanceOf(CampaignConfigInvalidException.class);
        assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                CampaignType.CONNECT_BY_AGENT, json("{}")))
                .isInstanceOf(CampaignConfigInvalidException.class);
    }

    // === dispatch ===

    @Test
    @DisplayName("Dispatch: every supported type resolves its typed representation")
    void dispatchPerType() {
        assertThat(CampaignTypeConfig.fromTypeConfig(CampaignType.PLAYFILE, null).campaignType())
                .isEqualTo(CampaignType.PLAYFILE);
        assertThat(CampaignTypeConfig.fromTypeConfig(
                CampaignType.DTMF, json("{\"dtmf\": {\"expected\": \"5\"}}")).campaignType())
                .isEqualTo(CampaignType.DTMF);
        assertThat(CampaignTypeConfig.fromTypeConfig(
                CampaignType.CONNECT_BY_AGENT, json("{\"x\": 1}")).campaignType())
                .isEqualTo(CampaignType.CONNECT_BY_AGENT);
    }
}
