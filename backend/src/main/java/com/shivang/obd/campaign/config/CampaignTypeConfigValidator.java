package com.shivang.obd.campaign.config;

import com.shivang.obd.campaign.CampaignType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Single ownership boundary for campaign type-configuration validation
 * (VB-6A): structural/type correctness of a campaign's {@code typeConfig}
 * for its {@link CampaignType}.
 * <p>
 * This is deliberately distinct from resource usability, which remains
 * owned by {@code CampaignResourceValidationService} (DID/audio/TTS state).
 * Write-time paths (create/update/activation) and snapshot materialization
 * both delegate here so validation is never scattered or duplicated.
 */
@Component
public class CampaignTypeConfigValidator {

    /**
     * Validates the typeConfig payload for the given campaign type.
     *
     * @throws CampaignConfigInvalidException if the payload is invalid for
     *         the type (deterministic, never silently ignored)
     */
    public CampaignTypeConfig validateAndParse(CampaignType type, JsonNode typeConfig) {
        return CampaignTypeConfig.fromTypeConfig(type, typeConfig);
    }
}
