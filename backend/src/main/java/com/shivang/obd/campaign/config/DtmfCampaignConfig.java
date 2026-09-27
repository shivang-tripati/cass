package com.shivang.obd.campaign.config;

import com.shivang.obd.campaign.CampaignType;
import com.shivang.obd.voice.dtmf.DtmfConfig;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed configuration for {@link CampaignType#DTMF} campaigns (VB-6A).
 * <p>
 * Delegates validation to the existing strict {@link DtmfConfig} parser so
 * the persisted JSON contract (and every validation rule) is preserved
 * byte-for-byte: {@code dtmf.{expected, maxDigits, terminator, timeoutSecs,
 * action}}. This type adds no fields and relaxes no rule — it wraps the
 * proven VB-2 parser in the unified typed-configuration boundary.
 */
public record DtmfCampaignConfig(
        String expected,
        int maxDigits,
        String terminator,
        int timeoutSecs,
        String action,
        ConfigSchemaVersion schemaVersion) implements CampaignTypeConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public DtmfCampaignConfig {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
    }

    @Override
    public CampaignType campaignType() {
        return CampaignType.DTMF;
    }

    @Override
    public JsonNode toJson() {
        ObjectNode dtmf = JsonNodeFactory.instance.objectNode();
        dtmf.put("expected", expected);
        dtmf.put("maxDigits", maxDigits);
        if (terminator != null) {
            dtmf.put("terminator", terminator);
        }
        dtmf.put("timeoutSecs", timeoutSecs);
        dtmf.put("action", action);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("dtmf", dtmf);
        return root;
    }

    static DtmfCampaignConfig fromTypeConfig(JsonNode typeConfig) {
        // Same contract as the runtime path: absent/malformed → typed failure.
        // The legacy parser's exception is wrapped into the unified typed-config
        // exception so all type parsers fail with one deterministic type.
        DtmfConfig parsed;
        try {
            parsed = DtmfConfig.fromTypeConfig(typeConfig);
        } catch (com.shivang.obd.voice.dtmf.DtmfConfigInvalidException e) {
            throw new CampaignConfigInvalidException(e.getMessage());
        }
        return new DtmfCampaignConfig(
                parsed.expected(),
                parsed.maxDigits(),
                parsed.getTerminator().orElse(null),
                parsed.timeoutSecs(),
                parsed.action(),
                ConfigSchemaVersion.V1);
    }

    /** View of the delegated VB-2 configuration. */
    public DtmfConfig toDtmfConfig() {
        return new DtmfConfig(expected, maxDigits, terminator, timeoutSecs, action);
    }

    /** Canonical JSON text (used by tests). */
    public String toJsonString() {
        return MAPPER.writeValueAsString(toJson());
    }
}
