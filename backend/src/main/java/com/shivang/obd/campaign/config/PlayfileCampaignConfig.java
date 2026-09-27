package com.shivang.obd.campaign.config;

import com.shivang.obd.campaign.CampaignType;
import com.shivang.obd.campaign.ContentMode;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Typed configuration for {@link CampaignType#PLAYFILE} campaigns (VB-6A).
 * <p>
 * PLAYFILE currently carries no type-specific JSON configuration: content
 * selection lives in the strongly typed columns ({@code content_mode},
 * {@code audio_asset_id}). The typeConfig payload is therefore accepted as
 * absent, null, or an empty JSON object — matching existing persisted rows
 * and API behavior — while any non-empty payload is rejected
 * deterministically instead of silently ignored. Playback semantics (which
 * audio, approved, tenant-owned) remain dynamic runtime validations.
 */
public record PlayfileCampaignConfig(
        ConfigSchemaVersion schemaVersion) implements CampaignTypeConfig {

    public PlayfileCampaignConfig {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
    }

    @Override
    public CampaignType campaignType() {
        return CampaignType.PLAYFILE;
    }

    @Override
    public ConfigSchemaVersion schemaVersion() {
        return schemaVersion;
    }

    @Override
    public JsonNode toJson() {
        // PLAYFILE persists no type-specific payload today.
        return JsonNodeFactory.instance.objectNode();
    }

    static PlayfileCampaignConfig fromTypeConfig(JsonNode typeConfig) {
        if (typeConfig == null || typeConfig.isNull()
                || (typeConfig.isObject() && typeConfig.isEmpty())) {
            return new PlayfileCampaignConfig(ConfigSchemaVersion.V1);
        }
        throw new CampaignConfigInvalidException(
                "PLAYFILE campaigns do not support type-specific configuration; "
                        + "content is selected via contentMode and audioAssetId");
    }
}
