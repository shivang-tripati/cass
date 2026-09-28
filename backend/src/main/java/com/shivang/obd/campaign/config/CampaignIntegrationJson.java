package com.shivang.obd.campaign.config;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * Jackson 3 bindings for the campaign integration configuration (VB-7C.2).
 *
 * <h2>Why deserialization needs binding</h2>
 *
 * <p>{@code CampaignIntegrationConfig}, {@link WebhookConfig} and
 * {@link ReportPrivacyConfig} each have a hand-written, strict
 * {@code fromJson(JsonNode)}. That authority rejects unknown fields, unknown
 * events, duplicate events, unsupported URL schemes and credentials embedded in
 * a URL.
 *
 * <p>Without these bindings <b>none of that applied to the REST API.</b> Jackson
 * deserialised the records directly, using its own default rules, which
 * <em>ignore</em> unknown properties. A payload of
 * {@code {"webhook":{"secret":"hunter2"}}} was accepted with 201 and the secret
 * silently dropped — the exact behaviour the audit found and this phase exists to
 * remove, still live on the most important path. A slice test caught it; a unit
 * test over {@code fromJson} would not have, because it never exercised Jackson.
 *
 * <h2>Why the enums need both directions</h2>
 *
 * <p>{@link WebhookEvent} serializes as its explicit public identifier rather
 * than its Java constant name. Without that, the API would publish
 * {@code ATTEMPT_COMPLETED} — an internal identifier — which is exactly what
 * locked OD-2 forbids. {@link ReportPrivacy} is bound for symmetry and to reject
 * unknown values on the way in.
 *
 * <h2>Why the records are NOT given a serializer</h2>
 *
 * <p>An earlier version bound serialization on the three records too. That was
 * wrong for a reason worth recording: a type with a custom serializer is opaque
 * to springdoc's model resolution, so the generated OpenAPI document lost the
 * record's structure entirely — no {@code type}, no properties, no enum. The
 * records are plain bean-shaped values, so Jackson's default record handling
 * already produces exactly the right JSON, with the enum serializers supplying
 * the public identifiers. Binding only the deserializers keeps strictness
 * <em>and</em> a faithful generated contract.
 *
 * <h2>One authority, not two rule sets</h2>
 *
 * <p>These adapters are the only place Jackson meets this model. REST, the
 * campaign mapper and the tests all go through the same static
 * {@code fromJson}/{@code toJson} methods, so a rule cannot be enforced on the
 * database path and forgotten on the HTTP path. There is no second validation
 * framework here and no duplicated logic.
 *
 * <h2>Error mapping</h2>
 *
 * <p>A rejected payload is raised as {@link CampaignConfigInvalidException},
 * which is unchecked. Jackson wraps it and Spring reports
 * {@code HttpMessageNotReadableException}, which the existing
 * {@code GlobalExceptionHandler} maps to <b>400 "Malformed request body."</b> — the
 * same status a {@code BusinessException} validation failure produces. No new
 * error envelope is introduced, and a client mistake can never surface as a 500.
 */
public final class CampaignIntegrationJson {

    private CampaignIntegrationJson() {
    }

    /** Parses a whole configuration through {@code fromJson}, strictly. */
    public static class ConfigDeserializer extends ValueDeserializer<CampaignIntegrationConfig> {
        @Override
        public CampaignIntegrationConfig deserialize(JsonParser parser,
                DeserializationContext context) throws JacksonException {
            JsonNode node = parser.readValueAsTree();
            return CampaignIntegrationConfig.fromJson(node);
        }
    }

    /** Parses a webhook block through {@code fromJson}, strictly. */
    public static class WebhookDeserializer extends ValueDeserializer<WebhookConfig> {
        @Override
        public WebhookConfig deserialize(JsonParser parser,
                DeserializationContext context) throws JacksonException {
            JsonNode node = parser.readValueAsTree();
            return WebhookConfig.fromJson(node);
        }
    }

    /** Parses a report privacy block through {@code fromJson}, strictly. */
    public static class PrivacyDeserializer extends ValueDeserializer<ReportPrivacyConfig> {
        @Override
        public ReportPrivacyConfig deserialize(JsonParser parser,
                DeserializationContext context) throws JacksonException {
            JsonNode node = parser.readValueAsTree();
            return ReportPrivacyConfig.fromJson(node);
        }
    }

    /**
     * Writes an event as its explicit public identifier, never as the Java
     * constant name. This is what keeps internal naming out of the API.
     */
    public static class EventSerializer extends ValueSerializer<WebhookEvent> {
        @Override
        public void serialize(WebhookEvent value, JsonGenerator gen,
                SerializationContext context) throws JacksonException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(value.publicValue());
        }
    }

    /** Reads an event from its public identifier, rejecting unknown values. */
    public static class EventDeserializer extends ValueDeserializer<WebhookEvent> {
        @Override
        public WebhookEvent deserialize(JsonParser parser, DeserializationContext context)
                throws JacksonException {
            return WebhookEvent.fromPublicValue(parser.getValueAsString());
        }
    }

    /** Writes a privacy policy as its explicit public value. */
    public static class PrivacyPolicySerializer extends ValueSerializer<ReportPrivacy> {
        @Override
        public void serialize(ReportPrivacy value, JsonGenerator gen,
                SerializationContext context) throws JacksonException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(value.value());
        }
    }

    /** Reads a privacy policy from its public value, rejecting unknown values. */
    public static class PrivacyPolicyDeserializer extends ValueDeserializer<ReportPrivacy> {
        @Override
        public ReportPrivacy deserialize(JsonParser parser, DeserializationContext context)
                throws JacksonException {
            return ReportPrivacy.require(parser.getValueAsString());
        }
    }
}
