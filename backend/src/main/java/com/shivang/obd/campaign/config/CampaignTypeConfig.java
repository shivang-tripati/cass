package com.shivang.obd.campaign.config;

import com.shivang.obd.campaign.CampaignType;
import tools.jackson.databind.JsonNode;

/**
 * Type-aware campaign type configuration (VB-6A): the strict typed domain
 * representation of one campaign type's {@code typeConfig} payload.
 * <p>
 * One implementation per supported {@link CampaignType}; unknown types have
 * no representation by construction. Parsing is strict and total — malformed
 * or type-mismatched payloads throw {@link CampaignConfigInvalidException}
 * and never silently degrade. The JSONB storage contract is unchanged: the
 * same JSON a client wrote is what is parsed here.
 */
public sealed interface CampaignTypeConfig
        permits PlayfileCampaignConfig, DtmfCampaignConfig, ConnectByAgentCampaignConfig {

    /** The campaign type this configuration belongs to. */
    CampaignType campaignType();

    /** How this configuration object reads from the campaign's typeConfig. */
    ConfigSchemaVersion schemaVersion();

    /**
     * Canonical JSON representation of this configuration — exactly the
     * persisted typeConfig shape for the type (compatibility codec).
     */
    JsonNode toJson();

    /**
     * Parses and validates the typeConfig payload for a campaign type.
     *
     * @throws CampaignConfigInvalidException if the payload is absent,
     *         malformed, or shaped for a different campaign type
     */
    static CampaignTypeConfig fromTypeConfig(CampaignType type, JsonNode typeConfig) {
        return switch (type) {
            case PLAYFILE -> PlayfileCampaignConfig.fromTypeConfig(typeConfig);
            case DTMF -> DtmfCampaignConfig.fromTypeConfig(typeConfig);
            case CONNECT_BY_AGENT -> ConnectByAgentCampaignConfig.fromTypeConfig(typeConfig);
        };
    }
}
