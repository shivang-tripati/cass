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
        permits PlayfileCampaignConfig, DtmfCampaignConfig, IvrCampaignConfig,
                ConnectByAgentCampaignConfig, MissedCallCampaignConfig {

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
     * <p>VB-6F: a DTMF campaign now has two valid shapes, so DTMF dispatches on
     * which key is present. A payload carrying {@code "ivr"} references a
     * reusable IVR tree; otherwise it is the original single-level
     * {@code {"dtmf":{…}}} contract, parsed exactly as before. IVR is preferred
     * when both keys somehow appear, so the read stays total and deterministic
     * rather than depending on a validation gap.
     *
     * @throws CampaignConfigInvalidException if the payload is absent,
     *         malformed, or shaped for a different campaign type
     */
    static CampaignTypeConfig fromTypeConfig(CampaignType type, JsonNode typeConfig) {
        return switch (type) {
            case PLAYFILE -> PlayfileCampaignConfig.fromTypeConfig(typeConfig);
            case DTMF -> IvrCampaignConfig.selectsIvr(typeConfig)
                    ? IvrCampaignConfig.fromTypeConfig(typeConfig).orElseThrow()
                    : DtmfCampaignConfig.fromTypeConfig(typeConfig);
            case CONNECT_BY_AGENT -> ConnectByAgentCampaignConfig.fromTypeConfig(typeConfig);
            case MISSED_CALL -> MissedCallCampaignConfig.fromTypeConfig(typeConfig);
        };
    }
}
