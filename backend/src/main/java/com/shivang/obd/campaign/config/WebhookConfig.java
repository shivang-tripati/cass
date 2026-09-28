package com.shivang.obd.campaign.config;

import tools.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed campaign-level webhook configuration (VB-7C.2, locked OD-9).
 *
 * <h2>Configuration only — nothing is delivered</h2>
 *
 * <p>This record states what a customer <em>wants</em> the platform to do. It is
 * deliberately the whole of VB-7C.2's webhook surface: there is no transport, no
 * producer, no queue, no signer, no retry and no delivery record. Selecting
 * {@link WebhookEvent#ATTEMPT_COMPLETED} records intent; it does not cause an
 * HTTP request, and no such request can be issued by this platform today.
 *
 * <h2>Enabled/disabled semantics</h2>
 *
 * <pre>
 *   enabled = false   endpoint optional, events may be empty
 *   enabled = true    endpoint REQUIRED and valid, at least one event REQUIRED
 * </pre>
 *
 * <p>Two rules are worth stating explicitly because their absence is how
 * configuration drifts:
 * <ul>
 *   <li>An endpoint never <em>enables</em> a webhook. {@code enabled} is the
 *       only switch; storing a URL with {@code enabled=false} stores a dormant
 *       configuration.</li>
 *   <li>Events are never inferred from the endpoint. A campaign that enables a
 *       webhook with no event selection is a configuration error, not a request
 *       for "everything".</li>
 * </ul>
 *
 * <p>What <em>is</em> enforced regardless of {@code enabled} is the validity of
 * anything actually supplied: a present endpoint must be a usable URL, and every
 * supplied event must be supported and unique. A disabled webhook with no
 * endpoint and no events is valid; a disabled webhook carrying a malformed URL
 * is not, because storing garbage that no code will ever read is never correct.
 *
 * <h2>Secrets</h2>
 *
 * <p>No secret, API key, bearer token or signing key is stored here, and none
 * can be: this record has no field for one, and it rejects unknown JSON fields
 * outright. The endpoint validator additionally refuses URLs carrying
 * {@code userinfo}, so credentials cannot be smuggled in through the URL either.
 * Web-hook signing is a separate, unimplemented concern.
 *
 * <h2>On-disk shape</h2>
 *
 * <pre>{@code
 * "webhook": {
 *   "enabled": true,
 *   "endpoint": "https://example.com/hooks/campaign",
 *   "events": ["campaign.attempt.completed"]
 * }
 * }</pre>
 */
@JsonDeserialize(using = CampaignIntegrationJson.WebhookDeserializer.class)
public record WebhookConfig(
        Boolean enabled,
        String endpoint,
        List<WebhookEvent> events) {

    /** Root JSON key. */
    public static final String KEY = "webhook";

    /** Fields this configuration accepts. Anything else is rejected. */
    private static final String F_ENABLED = "enabled";
    private static final String F_ENDPOINT = "endpoint";
    private static final String F_EVENTS = "events";

    public WebhookConfig {
        boolean isEnabled = Boolean.TRUE.equals(enabled);
        if (isEnabled) {
            // An enabled webhook with nothing to talk to, or nothing to say, is
            // a configuration that can never do anything useful.
            if (endpoint == null || endpoint.isBlank()) {
                throw new CampaignConfigInvalidException(
                        "integrationConfig.webhook.endpoint is required when the webhook is "
                                + "enabled.");
            }
            if (events == null || events.isEmpty()) {
                throw new CampaignConfigInvalidException(
                        "integrationConfig.webhook.events must select at least one supported "
                                + "event when the webhook is enabled. Supported: "
                                + WebhookEvent.supportedValues()
                                + ". Note that no event is delivered yet.");
            }
        }
        if (endpoint != null && !endpoint.isBlank()) {
            endpoint = WebhookEndpointValidator.requireValid(endpoint);
        } else {
            endpoint = null;
        }
        // Normalise to a non-null, duplicate-free, deterministically ordered list.
        //
        // This is what makes the record's equality stable across a JSON round
        // trip. Without it, a webhook parsed from `{"enabled":false}` holds
        // `events == null` while re-parsing its own canonical output (which
        // writes `"events":[]`) holds an empty list - so a value that validated,
        // persisted and was read back would not equal itself, and the GET
        // response would differ from what was accepted on create.
        java.util.List<WebhookEvent> normalised =
                new java.util.ArrayList<>(events == null ? List.<WebhookEvent>of() : events);
        for (WebhookEvent e : normalised) {
            if (e == null) {
                throw new CampaignConfigInvalidException(
                        "integrationConfig.webhook.events must not contain a null entry.");
            }
        }
        if (new java.util.HashSet<>(normalised).size() != normalised.size()) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig.webhook.events must not contain duplicates.");
        }
        // Declaration order, so the persisted list is deterministic and two
        // clients selecting the same set produce byte-identical JSON.
        normalised.sort(java.util.Comparator.comparingInt(Enum::ordinal));
        events = List.copyOf(normalised);
    }

    /** A disabled webhook with no endpoint and no events. */
    public static WebhookConfig disabled() {
        return new WebhookConfig(Boolean.FALSE, null, List.of());
    }

    /** Convenience factory for an enabled webhook. */
    public static WebhookConfig of(String endpoint, Set<WebhookEvent> events) {
        return new WebhookConfig(Boolean.TRUE, endpoint, List.copyOf(events));
    }

    /** Whether the webhook is switched on. Never null. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** The configured events, or an empty list. Never null. */
    public List<WebhookEvent> eventsOrEmpty() {
        return events == null ? List.of() : events;
    }

    /** The configured endpoint, or null. */
    public String endpointOrNull() {
        return endpoint == null || endpoint.isBlank() ? null : endpoint;
    }

    /** Canonical JSON for this configuration. */
    public JsonNode toJson() {
        ObjectNode inner = JsonNodeFactory.instance.objectNode();
        inner.put(F_ENABLED, isEnabled());
        if (endpointOrNull() != null) {
            inner.put(F_ENDPOINT, endpointOrNull());
        }
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (WebhookEvent e : eventsOrEmpty()) {
            array.add(e.publicValue());
        }
        inner.set(F_EVENTS, array);
        return inner;
    }

    /**
     * Parses a webhook payload strictly.
     *
     * <p>A {@code null} or absent payload yields {@link #disabled()}: a campaign
     * simply has no webhook. A payload that is present but malformed throws,
     * because a customer who supplied a webhook block expects it to be honoured.
     */
    public static WebhookConfig fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return disabled();
        }
        if (!node.isObject()) {
            throw new CampaignConfigInvalidException(
                    "integrationConfig." + KEY + " must be an object.");
        }
        rejectUnknownFields(node);

        Boolean enabled = null;
        JsonNode enabledNode = node.get(F_ENABLED);
        if (enabledNode != null && !enabledNode.isNull()) {
            if (!enabledNode.isBoolean()) {
                throw new CampaignConfigInvalidException(
                        "integrationConfig." + KEY + "." + F_ENABLED + " must be true or false.");
            }
            enabled = enabledNode.asBoolean();
        }

        String endpoint = null;
        JsonNode endpointNode = node.get(F_ENDPOINT);
        if (endpointNode != null && !endpointNode.isNull()) {
            if (!endpointNode.isString()) {
                throw new CampaignConfigInvalidException(
                        "integrationConfig." + KEY + "." + F_ENDPOINT + " must be a string.");
            }
            endpoint = endpointNode.asString();
        }

        List<WebhookEvent> events = null;
        JsonNode eventsNode = node.get(F_EVENTS);
        if (eventsNode != null && !eventsNode.isNull()) {
            if (!eventsNode.isArray()) {
                throw new CampaignConfigInvalidException(
                        "integrationConfig." + KEY + "." + F_EVENTS + " must be an array of "
                                + "event identifiers.");
            }
            Set<WebhookEvent> resolved = WebhookEvent.resolveAll(readStrings(eventsNode));
            events = List.copyOf(resolved);
        }

        // A webhook block that omits `enabled` is treated as disabled rather than
        // guessed at, so the presence of a URL never switches anything on.
        return new WebhookConfig(enabled == null ? Boolean.FALSE : enabled, endpoint, events);
    }

    private static List<String> readStrings(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode element : array) {
            if (!element.isString()) {
                throw new CampaignConfigInvalidException(
                        "integrationConfig." + KEY + "." + F_EVENTS + " entries must be event "
                                + "identifier strings.");
            }
            values.add(element.asString());
        }
        return values;
    }

    private static void rejectUnknownFields(JsonNode node) {
        node.properties().forEach(entry -> {
            switch (entry.getKey()) {
                case F_ENABLED, F_ENDPOINT, F_EVENTS -> {
                }
                default -> throw new CampaignConfigInvalidException(
                        "integrationConfig." + KEY + "." + entry.getKey()
                                + " is not a supported field; supported: "
                                + F_ENABLED + ", " + F_ENDPOINT + ", " + F_EVENTS
                                + ". In particular this configuration stores no secrets; "
                                + "web-hook signing is not implemented.");
            }
        });
    }
}
