package com.shivang.obd.campaign.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * VB-7C.2: the typed campaign integration configuration contract.
 *
 * <p>Covers the three things the audit found missing: the configuration was
 * arbitrary JSON, "secrets are prohibited here" was an unenforced comment, and
 * the API echoed back whatever a client wrote. Each is now a typed, strictly
 * parsed, validated structure.
 */
class CampaignIntegrationConfigTest {

    private static JsonNode json(String raw) {
        return JsonMapper.builder().build().readTree(raw);
    }

    // ------------------------------------------------------------------
    // A. Absent / default
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("A. a campaign with no integrations")
    class Defaults {

        @Test
        @DisplayName("A1. null and an empty object both mean 'no integrations'")
        void absentIsDefault() {
            assertThat(CampaignIntegrationConfig.fromJson(null).webhook().isEnabled()).isFalse();
            assertThat(CampaignIntegrationConfig.fromJson(json("{}")).webhook().isEnabled())
                    .isFalse();
        }

        @Test
        @DisplayName("A2. the default report privacy is FULL, which is current platform "
                + "behaviour - nothing silently tightens")
        void defaultPrivacyPreservesCurrentBehaviour() {
            assertThat(CampaignIntegrationConfig.defaults().reportPrivacy().policy())
                    .isEqualTo(ReportPrivacy.FULL);
            assertThat(CampaignIntegrationConfig.fromJson(null).reportPrivacy().policy())
                    .isEqualTo(ReportPrivacy.FULL);
        }

        @Test
        @DisplayName("A3. an absent configuration stays null on the way to storage, so "
                + "'configured' stays distinguishable from 'defaulted'")
        void absentStaysNullOnStorage() {
            assertThat(CampaignIntegrationConfig.validateAndCanonicalize(null)).isNull();
            assertThat(CampaignIntegrationConfig.validateAndCanonicalize(json("{}"))).isNull();
        }
    }

    // ------------------------------------------------------------------
    // B. Webhook enabled / disabled semantics
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("B. webhook enabled/disabled semantics")
    class WebhookSemantics {

        @Test
        @DisplayName("B1. disabled with no endpoint and no events is valid")
        void disabledWithNothing() {
            var config = CampaignIntegrationConfig.fromJson(
                    json("{\"webhook\":{\"enabled\":false}}"));

            assertThat(config.webhook().isEnabled()).isFalse();
            assertThat(config.webhook().endpointOrNull()).isNull();
            assertThat(config.webhook().eventsOrEmpty()).isEmpty();
        }

        @Test
        @DisplayName("B2. disabled with no events is valid")
        void disabledWithNoEvents() {
            var config = CampaignIntegrationConfig.fromJson(
                    json("{\"webhook\":{\"enabled\":false,\"events\":[]}}"));

            assertThat(config.webhook().eventsOrEmpty()).isEmpty();
        }

        @Test
        @DisplayName("B3. enabled with a valid HTTPS endpoint and a valid event is valid")
        void enabledAndComplete() {
            var config = CampaignIntegrationConfig.fromJson(json(
                    "{\"webhook\":{\"enabled\":true,"
                        + "\"endpoint\":\"https://example.com/hooks/campaign\","
                        + "\"events\":[\"campaign.attempt.completed\"]}}"));

            assertThat(config.isWebhookEnabled()).isTrue();
            assertThat(config.selectedEvents())
                    .containsExactly(WebhookEvent.ATTEMPT_COMPLETED);
        }

        @Test
        @DisplayName("B4. enabled without an endpoint is rejected")
        void enabledWithoutEndpoint() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(
                    json("{\"webhook\":{\"enabled\":true,"
                        + "\"events\":[\"campaign.attempt.completed\"]}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("endpoint is required");
        }

        @Test
        @DisplayName("B5. enabled with a blank endpoint is rejected")
        void enabledWithBlankEndpoint() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(
                    json("{\"webhook\":{\"enabled\":true,\"endpoint\":\"   \","
                        + "\"events\":[\"campaign.attempt.completed\"]}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("endpoint is required");
        }

        @Test
        @DisplayName("B6. enabled without any event is rejected - events are never inferred")
        void enabledWithoutEvents() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(
                    json("{\"webhook\":{\"enabled\":true,"
                        + "\"endpoint\":\"https://example.com/h\"}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("at least one");
        }

        @Test
        @DisplayName("B7. an endpoint never enables a webhook by itself")
        void endpointDoesNotEnable() {
            var config = CampaignIntegrationConfig.fromJson(json(
                    "{\"webhook\":{\"endpoint\":\"https://example.com/h\","
                        + "\"events\":[\"campaign.attempt.completed\"]}}"));

            assertThat(config.webhook().isEnabled())
                    .as("presence of an endpoint must not switch the webhook on")
                    .isFalse();
        }

        @Test
        @DisplayName("B8. a DISABLED webhook never fails on missing endpoint or events")
        void disabledIsNeverBlocked() {
            // The readiness-relevant case: a configured-but-off webhook must not
            // make a campaign unready, so this shape must always parse.
            var config = CampaignIntegrationConfig.fromJson(json(
                    "{\"webhook\":{\"enabled\":false}}"));

            assertThat(config.webhook().isEnabled()).isFalse();
        }

        @Test
        @DisplayName("B9. a present endpoint is validated even when the webhook is "
                + "disabled - garbage is never stored")
        void suppliedEndpointIsAlwaysValidated() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(
                    json("{\"webhook\":{\"enabled\":false,\"endpoint\":\"not a url\"}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }
    }

    // ------------------------------------------------------------------
    // C. Endpoint validation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("C. endpoint validation")
    class EndpointValidation {

        @ParameterizedTest(name = "accepts {0}")
        @ValueSource(strings = {
            "https://example.com",
            "https://example.com/hooks/campaign",
            "http://internal-service.local:8080/hook",
            "https://example.com:8443/a/b?c=1",
        })
        @DisplayName("C1. absolute http/https URLs are accepted")
        void validEndpoints(String endpoint) {
            assertThat(WebhookEndpointValidator.isValid(endpoint))
                    .as("%s", endpoint).isTrue();
        }

        @ParameterizedTest(name = "rejects {0}")
        @ValueSource(strings = {
            "not a url",
            "example.com/hook",
            "/relative/hook",
            "file:///etc/passwd",
            "ftp://example.com/f",
            "javascript:alert(1)",
            "data:text/plain,hi",
            "jar:file:///x.jar!/y",
        })
        @DisplayName("C2. malformed URLs and non-web schemes are rejected")
        void invalidEndpoints(String endpoint) {
            assertThat(WebhookEndpointValidator.isValid(endpoint))
                    .as("%s", endpoint).isFalse();
        }

        @ParameterizedTest(name = "rejects blank form: {0}")
        @ValueSource(strings = {"", "   "})
        @DisplayName("C3. blank endpoints are rejected")
        void blankEndpoints(String endpoint) {
            assertThat(WebhookEndpointValidator.isValid(endpoint)).isFalse();
        }

        @Test
        @DisplayName("C4. a URL carrying embedded credentials is rejected - this "
                + "configuration must not be a secret store")
        void credentialsInUrlRejected() {
            assertThatThrownBy(() -> WebhookEndpointValidator.requireValid(
                    "https://user:apikey123@example.com/hook"))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("must not embed credentials");
        }

        @Test
        @DisplayName("C5. an over-long endpoint is rejected")
        void overLongEndpoint() {
            String endpoint = "https://example.com/"
                    + "a".repeat(WebhookEndpointValidator.MAX_ENDPOINT_LENGTH);
            assertThat(WebhookEndpointValidator.isValid(endpoint)).isFalse();
        }

        @Test
        @DisplayName("C6. a valid endpoint is returned trimmed")
        void endpointIsTrimmed() {
            assertThat(WebhookEndpointValidator.requireValid("  https://example.com/h  "))
                    .isEqualTo("https://example.com/h");
        }
    }

    // ------------------------------------------------------------------
    // D. Event selection
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("D. event selection")
    class EventSelection {

        @Test
        @DisplayName("D1. every supported event is accepted")
        void allEventsAccepted() {
            var config = CampaignIntegrationConfig.fromJson(json(
                    "{\"webhook\":{\"enabled\":true,"
                        + "\"endpoint\":\"https://example.com/h\",\"events\":["
                        + "\"campaign.attempt.completed\","
                        + "\"campaign.attempt.failed\","
                        + "\"campaign.attempt.cancelled\"]}}"));

            assertThat(config.selectedEvents()).containsExactlyInAnyOrder(
                    WebhookEvent.ATTEMPT_COMPLETED,
                    WebhookEvent.ATTEMPT_FAILED,
                    WebhookEvent.ATTEMPT_CANCELLED);
        }

        @Test
        @DisplayName("D2. an unknown event is rejected, never silently discarded")
        void unknownEventRejected() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(json(
                    "{\"webhook\":{\"enabled\":true,"
                        + "\"endpoint\":\"https://example.com/h\","
                        + "\"events\":[\"campaign.attempt.teleported\"]}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("not a supported webhook event");
        }

        @Test
        @DisplayName("D3. a duplicate event is rejected rather than silently collapsed")
        void duplicateEventRejected() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(json(
                    "{\"webhook\":{\"enabled\":true,"
                        + "\"endpoint\":\"https://example.com/h\",\"events\":["
                        + "\"campaign.attempt.completed\","
                        + "\"campaign.attempt.completed\"]}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("duplicate");
        }

        @Test
        @DisplayName("D4. a non-string event entry is rejected")
        void nonStringEventRejected() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(json(
                    "{\"webhook\":{\"enabled\":true,"
                        + "\"endpoint\":\"https://example.com/h\",\"events\":[42]}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("D5. event order in storage is deterministic, so two clients choosing the "
                + "same set produce byte-identical JSON")
        void eventOrderIsDeterministic() {
            String ascending = "{\"webhook\":{\"enabled\":true,"
                    + "\"endpoint\":\"https://example.com/h\",\"events\":["
                    + "\"campaign.attempt.completed\",\"campaign.attempt.failed\"]}}";
            String descending = "{\"webhook\":{\"enabled\":true,"
                    + "\"endpoint\":\"https://example.com/h\",\"events\":["
                    + "\"campaign.attempt.failed\",\"campaign.attempt.completed\"]}}";

            assertThat(CampaignIntegrationConfig.fromJson(json(ascending)).toJson())
                    .isEqualTo(CampaignIntegrationConfig.fromJson(json(descending)).toJson());
        }

        @Test
        @DisplayName("D6. the error message names the supported identifiers")
        void errorListsSupportedEvents() {
            assertThatThrownBy(() -> WebhookEvent.fromPublicValue("nope"))
                    .hasMessageContaining("campaign.attempt.completed")
                    .hasMessageContaining("campaign.attempt.failed")
                    .hasMessageContaining("campaign.attempt.cancelled");
        }
    }

    // ------------------------------------------------------------------
    // E. Strictness and the no-secrets contract
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("E. strict parsing and the no-secrets contract")
    class StrictnessAndSecrets {

        @Test
        @DisplayName("E1. an unknown top-level field is rejected")
        void unknownTopLevelFieldRejected() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(
                    json("{\"slack\":{\"url\":\"x\"}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("not a supported field");
        }

        @Test
        @DisplayName("E2. an unknown webhook field is rejected")
        void unknownWebhookFieldRejected() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(
                    json("{\"webhook\":{\"enabled\":false,\"retries\":5}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("retries");
        }

        @ParameterizedTest(name = "rejects secret-like field: {0}")
        @ValueSource(strings = {
            "secret", "apiKey", "api_key", "token", "bearerToken",
            "signingKey", "hmacSecret", "password",
        })
        @DisplayName("E3. secret-like fields cannot be persisted - the configuration has no "
                + "field for a credential and unknown fields are refused")
        void secretLikeFieldsRejected(String field) {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(json(
                    "{\"webhook\":{\"enabled\":false,\"" + field + "\":\"hunter2\"}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("not a supported field");
        }

        @Test
        @DisplayName("E4. a top-level secret-like field is rejected too")
        void topLevelSecretRejected() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(
                    json("{\"apiKey\":\"hunter2\"}")))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("E5. the canonical serialization carries no secret-shaped key")
        void canonicalFormHasNoSecretKey() {
            JsonNode canonical = CampaignIntegrationConfig.fromJson(json(
                    "{\"webhook\":{\"enabled\":true,"
                        + "\"endpoint\":\"https://example.com/h\","
                        + "\"events\":[\"campaign.attempt.completed\"]}}")).toJson();

            String text = canonical.toString().toLowerCase(java.util.Locale.ROOT);
            for (String forbidden : List.of("secret", "apikey", "token", "password", "key")) {
                assertThat(text)
                        .as("canonical form must not contain %s", forbidden)
                        .doesNotContain(forbidden);
            }
        }

        @Test
        @DisplayName("E6. a non-object integrationConfig is rejected")
        void nonObjectRejected() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(json("[]")))
                    .isInstanceOf(CampaignConfigInvalidException.class);
        }

        @Test
        @DisplayName("E7. a wrong-typed enabled flag is rejected")
        void wrongTypedEnabledRejected() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(
                    json("{\"webhook\":{\"enabled\":\"yes\"}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("true or false");
        }
    }

    // ------------------------------------------------------------------
    // F. Round trip
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("F. persistence round trip")
    class RoundTrip {

        @Test
        @DisplayName("F1. a full configuration round-trips through JSON unchanged")
        void fullRoundTrip() {
            String raw = "{\"webhook\":{\"enabled\":true,"
                    + "\"endpoint\":\"https://example.com/hooks/campaign\",\"events\":["
                    + "\"campaign.attempt.completed\",\"campaign.attempt.failed\","
                    + "\"campaign.attempt.cancelled\"]},"
                    + "\"reportPrivacy\":{\"policy\":\"MASKED\"}}";

            var parsed = CampaignIntegrationConfig.fromJson(json(raw));
            var reparsed = CampaignIntegrationConfig.fromJson(parsed.toJson());

            assertThat(reparsed).isEqualTo(parsed);
            assertThat(reparsed.reportPrivacy().policy()).isEqualTo(ReportPrivacy.MASKED);
            assertThat(reparsed.isWebhookEnabled()).isTrue();
        }

        @Test
        @DisplayName("F2. a disabled webhook round-trips")
        void disabledRoundTrip() {
            var parsed = CampaignIntegrationConfig.fromJson(
                    json("{\"webhook\":{\"enabled\":false}}"));
            assertThat(CampaignIntegrationConfig.fromJson(parsed.toJson()))
                    .isEqualTo(parsed);
        }

        @Test
        @DisplayName("F3. MASKED report privacy round-trips")
        void privacyRoundTrip() {
            var parsed = CampaignIntegrationConfig.fromJson(
                    json("{\"reportPrivacy\":{\"policy\":\"MASKED\"}}"));
            assertThat(CampaignIntegrationConfig.fromJson(parsed.toJson()))
                    .isEqualTo(parsed);
        }
    }

    // ------------------------------------------------------------------
    // G. Report privacy
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("G. report privacy")
    class Privacy {

        @ParameterizedTest(name = "accepts {0}")
        @CsvSource({"FULL", "MASKED"})
        @DisplayName("G1. each supported policy is accepted")
        void supportedPolicies(String policy) {
            var config = CampaignIntegrationConfig.fromJson(
                    json("{\"reportPrivacy\":{\"policy\":\"" + policy + "\"}}"));
            assertThat(config.reportPrivacy().policy().value()).isEqualTo(policy);
        }

        @Test
        @DisplayName("G2. an unsupported policy is rejected and the message lists the "
                + "supported ones")
        void unsupportedPolicyRejected() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(
                    json("{\"reportPrivacy\":{\"policy\":\"ANONYMISED\"}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("FULL")
                    .hasMessageContaining("MASKED");
        }

        @Test
        @DisplayName("G3. a missing policy inside a present block is rejected, not defaulted")
        void missingPolicyRejected() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(
                    json("{\"reportPrivacy\":{}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("policy is required");
        }

        @Test
        @DisplayName("G4. an unknown reportPrivacy field is rejected")
        void unknownPrivacyFieldRejected() {
            assertThatThrownBy(() -> CampaignIntegrationConfig.fromJson(
                    json("{\"reportPrivacy\":{\"policy\":\"FULL\",\"retentionDays\":30}}")))
                    .isInstanceOf(CampaignConfigInvalidException.class)
                    .hasMessageContaining("retentionDays");
        }
    }
}
