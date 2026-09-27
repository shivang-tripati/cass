package com.shivang.obd.campaign.config;

import com.shivang.obd.campaign.CampaignType;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Typed configuration for {@link CampaignType#CONNECT_BY_AGENT} campaigns
 * (VB-6A).
 * <p>
 * CONNECT_BY_AGENT currently carries no type-specific JSON configuration:
 * agent selection, reservation and bridging are service-driven
 * (deterministic eligibility scan), and the write-time contract only
 * requires a <em>present</em> non-empty typeConfig object (historical
 * decision — kept for compatibility). This typed representation therefore
 * preserves that contract exactly: a non-empty object (any shape) is
 * accepted as the legacy payload; absent/null is rejected with the same
 * semantics as the existing write-time rule. No queue or agent-routing
 * configuration is invented in this phase.
 */
public record ConnectByAgentCampaignConfig(
        JsonNode legacyPayload,
        ConfigSchemaVersion schemaVersion) implements CampaignTypeConfig {

    public ConnectByAgentCampaignConfig {
        Objects.requireNonNull(legacyPayload, "legacyPayload");
        if (legacyPayload.isNull() || !legacyPayload.isObject() || legacyPayload.isEmpty()) {
            throw new CampaignConfigInvalidException(
                    "CONNECT_BY_AGENT requires a non-empty typeConfig object");
        }
        Objects.requireNonNull(schemaVersion, "schemaVersion");
    }

    @Override
    public CampaignType campaignType() {
        return CampaignType.CONNECT_BY_AGENT;
    }

    @Override
    public JsonNode toJson() {
        // Round-trips the legacy payload untouched (compatibility).
        return legacyPayload;
    }

    static ConnectByAgentCampaignConfig fromTypeConfig(JsonNode typeConfig) {
        if (typeConfig == null || typeConfig.isNull() || !typeConfig.isObject()
                || typeConfig.isEmpty()) {
            throw new CampaignConfigInvalidException(
                    "CONNECT_BY_AGENT campaigns require type-specific configuration");
        }
        return new ConnectByAgentCampaignConfig(typeConfig, ConfigSchemaVersion.V1);
    }
}
