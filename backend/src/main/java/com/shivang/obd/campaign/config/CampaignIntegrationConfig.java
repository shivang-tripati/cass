package com.shivang.obd.campaign.config;

import tools.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed campaign integration configuration (VB-7C.2) — the campaign aggregate's
 * outbound-integration and reporting-privacy contract.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Before VB-7C.2, {@code campaigns.integration_config} was an untyped
 * {@code JsonNode} that any client could write to and that no code ever read. It
 * was stored verbatim, echoed verbatim through the API, copied into every
 * campaign clone, and explicitly excluded from the execution snapshot. The
 * javadoc on the entity claimed "secrets are prohibited here"; nothing enforced
 * it. The audit (§7.3) recorded this as a write-only, unvalidated sink.
 *
 * <p>This record makes the contract explicit. The <b>storage does not change</b>
 * — the same {@code integration_config} JSONB column is reused, exactly as
 * {@code type_config} is — so <b>no migration is required</b>. What changes is
 * that the JSON must now parse into this shape, strictly, or the campaign is
 * rejected.
 *
 * <h2>Strictness</h2>
 *
 * <p>Unknown fields are rejected rather than stored. A field the platform does
 * not understand is a field the platform will never honour, and silently
 * persisting it is how a customer comes to believe a capability exists. This is
 * the same contract the four {@link CampaignTypeConfig} implementations already
 * enforce.
 *
 * <h2>No secrets, ever</h2>
 *
 * <p>This configuration has no field capable of holding a credential, and its
 * strict parsing means a caller cannot smuggle one in under an unrecognised
 * key. {@link WebhookEndpointValidator} additionally refuses endpoints carrying
 * {@code userinfo}. Web-hook signing is unimplemented and unstorageable.
 *
 * <h2>What this configuration does NOT do</h2>
 *
 * <p>Nothing. There is no webhook delivery, no signing, no delivery retry, no
 * delivery persistence, no worker, no queue, no dispatcher, no report generation,
 * no export and no report runtime. A campaign with a fully valid webhook
 * configuration here receives no HTTP traffic from this platform, because
 * nothing here can send any.
 *
 * <h2>Snapshot participation (OD-3, deferred)</h2>
 *
 * <p>Deliberately still excluded from
 * {@link com.shivang.obd.campaign.CampaignConfigurationSnapshot}, for the same
 * reason as before and now for a stronger one: <b>nothing consumes this
 * configuration</b>, so freezing it would record an execution's intent to
 * deliver web-hooks — semantics no execution currently has.
 *
 * <p>The exclusion is a live hazard and is recorded as such. The moment the first
 * consumer appears, this configuration <b>must</b> be added to the snapshot in
 * the same phase, exactly as VB-6A did for retry and schedule. Shipping a
 * consumer while leaving the exclusion in place would let an operator edit a
 * campaign's endpoint and silently change the behaviour of an already-running
 * execution — the precise failure the immutable snapshot exists to prevent.
 *
 * <h2>On-disk shape</h2>
 *
 * <pre>{@code
 * {
 *   "webhook": {
 *     "enabled": true,
 *     "endpoint": "https://example.com/hooks/campaign",
 *     "events": ["campaign.attempt.completed", "campaign.attempt.failed"]
 *   },
 *   "reportPrivacy": { "policy": "MASKED" }
 * }
 * }</pre>
 */
@JsonDeserialize(using = CampaignIntegrationJson.ConfigDeserializer.class)
public record CampaignIntegrationConfig(
        WebhookConfig webhook,
        ReportPrivacyConfig reportPrivacy) {

    /** Root keys. */
    public static final String KEY_WEBHOOK = WebhookConfig.KEY;
    public static final String KEY_REPORT_PRIVACY = ReportPrivacyConfig.KEY;

    public CampaignIntegrationConfig {
        if (webhook == null) {
            webhook = WebhookConfig.disabled();
        }
        if (reportPrivacy == null) {
            reportPrivacy = ReportPrivacyConfig.defaults();
        }
    }

    /** The default: no webhook, and reports unchanged. */
    public static CampaignIntegrationConfig defaults() {
        return new CampaignIntegrationConfig(
                WebhookConfig.disabled(), ReportPrivacyConfig.defaults());
    }

    public WebhookConfig webhook() {
        return webhook;
    }

    public ReportPrivacyConfig reportPrivacy() {
        return reportPrivacy;
    }

    /** Whether a webhook is switched on. */
    public boolean isWebhookEnabled() {
        return webhook.isEnabled();
    }

    /** The selected events, or an empty set. */
    public Set<WebhookEvent> selectedEvents() {
        return webhook.eventsOrEmpty().stream()
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** Canonical JSON for this configuration. */
    public JsonNode toJson() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set(KEY_WEBHOOK, webhook.toJson());
        root.set(KEY_REPORT_PRIVACY, reportPrivacy.toJson());
        return root;
    }

    /**
     * Parses an integration configuration strictly and totally.
     *
     * <p>{@code null} or an empty object yields {@link #defaults()}, so a
     * campaign that never mentions integrations behaves exactly as it did
     * before this phase.
     *
     * @throws CampaignConfigInvalidException on any malformed or unsupported
     *         content
     */
    public static CampaignIntegrationConfig fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return defaults();
        }
        if (!node.isObject()) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig must be an object.");
        }
        if (node.isEmpty()) {
            return defaults();
        }

        node.properties().forEach(entry -> {
            switch (entry.getKey()) {
                case KEY_WEBHOOK, KEY_REPORT_PRIVACY -> {
                }
                default -> throw new CampaignConfigInvalidException(
                        "integrationConfig." + entry.getKey() + " is not a supported field; "
                                + "supported: " + KEY_WEBHOOK + ", " + KEY_REPORT_PRIVACY + ".");
            }
        });

        return new CampaignIntegrationConfig(
                WebhookConfig.fromJson(node.get(KEY_WEBHOOK)),
                ReportPrivacyConfig.fromJson(node.get(KEY_REPORT_PRIVACY)));
    }

    /**
     * Validates an integration configuration, returning the canonical
     * {@code null} when the caller supplied nothing.
     *
     * <p>{@link #fromJson} already validates by construction, so this exists to
     * give the write path a single obvious authority to call and a single place
     * for a {@code null} (meaning "no integrations configured") to be preserved,
     * rather than being normalised into a stored default object.
     */
    public static JsonNode validateAndCanonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        CampaignIntegrationConfig parsed = fromJson(node);
        JsonNode canonical = parsed.toJson();
        // An absent or empty payload stays absent: writing an explicit default
        // object into every campaign would be a silent change to every existing
        // row, and would make "configured" indistinguishable from "defaulted".
        if (node.isObject() && node.isEmpty()) {
            return null;
        }
        return canonical;
    }

    /** Convenience for tests and callers: the supported event identifiers. */
    public static List<WebhookEvent> supportedEvents() {
        return List.of(WebhookEvent.values());
    }
}
