package com.shivang.obd.campaign.config;

import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed campaign-level report privacy configuration (VB-7C.2, locked OD-10).
 *
 * <h2>Configuration only</h2>
 *
 * <p>Records the privacy policy a future reporting/export subsystem should apply
 * to this campaign. No report is generated, no export exists, and the existing
 * attempt-listing APIs are unchanged — they do not read this configuration and
 * still expose contact data exactly as before.
 *
 * <h2>Default</h2>
 *
 * <p>Absent configuration means {@link ReportPrivacy#FULL}, which is the
 * behaviour the platform already has. Defaulting to anything stricter would be a
 * silent change to every existing campaign, which OD-10 forbids.
 *
 * <h2>On-disk shape</h2>
 *
 * <pre>{@code "reportPrivacy": {"policy": "MASKED"}}</pre>
 */
@JsonDeserialize(using = CampaignIntegrationJson.PrivacyDeserializer.class)
public record ReportPrivacyConfig(ReportPrivacy policy) {

    /** Root JSON key. */
    public static final String KEY = "reportPrivacy";

    /** The single supported field. */
    private static final String F_POLICY = "policy";

    public ReportPrivacyConfig {
        if (policy == null) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig." + KEY + "." + F_POLICY + " is required; use \""
                            + ReportPrivacy.FULL.value() + "\" to declare that reports carry "
                            + "complete contact identifiers.");
        }
    }

    /** The default policy, matching current platform behaviour. */
    public static ReportPrivacyConfig defaults() {
        return new ReportPrivacyConfig(ReportPrivacy.FULL);
    }

    public ReportPrivacy policy() {
        return policy;
    }

    /** Canonical JSON for this configuration. */
    public JsonNode toJson() {
        ObjectNode inner = JsonNodeFactory.instance.objectNode();
        inner.put(F_POLICY, policy.value());
        return inner;
    }

    /**
     * Parses a report privacy payload strictly.
     *
     * <p>A {@code null} or absent payload yields {@link #defaults()}. A payload
     * that is present but unusable throws — a customer who supplied a policy
     * block expects it to be honoured, not silently replaced by the default.
     */
    public static ReportPrivacyConfig fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return defaults();
        }
        if (!node.isObject()) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig." + KEY + " must be an object with a \""
                            + F_POLICY + "\" field.");
        }
        node.properties().forEach(entry -> {
            if (!F_POLICY.equals(entry.getKey())) {
                throw new CampaignConfigInvalidException(
                        "integrationConfig." + KEY + "." + entry.getKey()
                                + " is not a supported field; supported: " + F_POLICY + ".");
            }
        });

        JsonNode policyNode = node.get(F_POLICY);
        if (policyNode == null || policyNode.isNull()) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig." + KEY + "." + F_POLICY + " is required. Supported: "
                            + ReportPrivacy.supportedValues() + ".");
        }
        if (!policyNode.isString()) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig." + KEY + "." + F_POLICY + " must be a string. "
                            + "Supported: " + ReportPrivacy.supportedValues() + ".");
        }
        return new ReportPrivacyConfig(ReportPrivacy.require(policyNode.asString()));
    }
}
