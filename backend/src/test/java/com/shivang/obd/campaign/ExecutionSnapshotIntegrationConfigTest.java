package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.config.CampaignIntegrationConfig;
import com.shivang.obd.campaign.config.ReportPrivacy;
import com.shivang.obd.campaign.config.WebhookEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * VB-7C.3 — the immutable execution snapshot must be authoritative for the
 * webhook + report-privacy configuration VB-7C.2 introduced, decided
 * <em>before</em> any consumer exists so the first delivery or reporting phase
 * inherits a safe snapshot rather than having to remember to extend it.
 *
 * <ul>
 *   <li>SNAP-IC-A — construction: endpoint, selected events and privacy level
 *       are all frozen, for enabled and disabled webhooks alike</li>
 *   <li>SNAP-IC-B — null/default semantics are deterministic and are NOT
 *       collapsed: "never configured" stays distinct from "configured to the
 *       default"</li>
 *   <li>SNAP-IC-C — the frozen payload is the canonical validated form, not the
 *       client's raw JSON</li>
 *   <li>SNAP-IC-D — the typed accessor fails loudly rather than inventing a
 *       default for a payload it cannot read</li>
 *   <li>SNAP-IC-E — the runtime boundary is structural: execution-time
 *       configuration cannot be obtained from a mutable {@link CampaignEntity}
 *       at all</li>
 *   <li>SNAP-IC-F — no versioning machinery was introduced alongside this</li>
 * </ul>
 *
 * <p>PostgreSQL persistence, reload, mutation isolation and tenant isolation
 * live in {@code ExecutionSnapshotIntegrationConfigPostgresIntegrationTest};
 * this class covers the pure semantics so they stay cheap to verify.
 */
class ExecutionSnapshotIntegrationConfigTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static CampaignEntity campaignWith(String integrationJson) {
        CampaignEntity c = new CampaignEntity();
        c.setTenantId(UUID.randomUUID());
        c.setName("vb7c3");
        c.setCampaignType(CampaignType.PLAYFILE);
        c.setStatus(CampaignStatus.SCHEDULED);
        c.setDidId(UUID.randomUUID());
        c.setContactGroupId(UUID.randomUUID());
        // A windowless UTC schedule; only the integration block matters here.
        c.setSchedule(new ScheduleSpec(null, null, null, "UTC", null, null));
        c.setRetryPolicy(new RetryPolicySpec(0, null, RetryStrategy.FIXED));
        c.setCallOnWhitelistNumbers(Boolean.FALSE);
        if (integrationJson != null) {
            c.setIntegrationConfig(JSON.readTree(integrationJson));
        }
        return c;
    }

    /** Freezes exactly the way {@code CampaignConfigurationService} does. */
    private static CampaignConfigurationSnapshot freeze(String integrationJson) {
        return freezeCampaign(campaignWith(integrationJson));
    }

    private static CampaignConfigurationSnapshot freezeCampaign(CampaignEntity campaign) {
        var typeConfig = new com.shivang.obd.campaign.config.CampaignTypeConfigValidator()
                .validateAndParse(campaign.getCampaignType(), campaign.getTypeConfig());
        return CampaignConfigurationService.toSnapshot(campaign, typeConfig);
    }

    // === SNAP-IC-A: everything execution-affecting is frozen ===

    @Nested
    @DisplayName("SNAP-IC-A: webhook and privacy are frozen at execution creation")
    class Construction {

        @Test
        @DisplayName("A1: an enabled webhook freezes its endpoint and the selected events")
        void enabledWebhookIsFrozenWholesale() {
            CampaignConfigurationSnapshot snapshot = freeze("""
                    {
                      "webhook": {
                        "enabled": true,
                        "endpoint": "https://example-a.test/webhook",
                        "events": ["campaign.attempt.completed"]
                      },
                      "reportPrivacy": { "policy": "MASKED" }
                    }
                    """);

            CampaignIntegrationConfig frozen = snapshot.asIntegrationConfig().orElseThrow();

            assertThat(frozen.isWebhookEnabled()).isTrue();
            assertThat(frozen.webhook().endpointOrNull())
                    .isEqualTo("https://example-a.test/webhook");
            assertThat(frozen.selectedEvents())
                    .containsExactly(WebhookEvent.ATTEMPT_COMPLETED);
            assertThat(frozen.reportPrivacy().policy()).isEqualTo(ReportPrivacy.MASKED);
        }

        @Test
        @DisplayName("A2: a disabled webhook is frozen as disabled, not dropped")
        void disabledWebhookIsStillFrozen() {
            CampaignConfigurationSnapshot snapshot = freeze("""
                    {
                      "webhook": { "enabled": false },
                      "reportPrivacy": { "policy": "FULL" }
                    }
                    """);

            CampaignIntegrationConfig frozen = snapshot.asIntegrationConfig().orElseThrow();

            assertThat(frozen.isWebhookEnabled()).isFalse();
            assertThat(frozen.webhook().endpointOrNull()).isNull();
            assertThat(frozen.selectedEvents()).isEmpty();
            assertThat(frozen.reportPrivacy().policy()).isEqualTo(ReportPrivacy.FULL);
        }

        @Test
        @DisplayName("A3: a multi-event selection is frozen completely")
        void multipleSelectedEventsAreFrozen() {
            CampaignConfigurationSnapshot snapshot = freeze("""
                    {
                      "webhook": {
                        "enabled": true,
                        "endpoint": "https://example-a.test/webhook",
                        "events": ["campaign.attempt.completed", "campaign.attempt.failed"]
                      }
                    }
                    """);

            assertThat(snapshot.asIntegrationConfig().orElseThrow().selectedEvents())
                    .containsExactlyInAnyOrder(
                            WebhookEvent.ATTEMPT_COMPLETED, WebhookEvent.ATTEMPT_FAILED);
        }

        @Test
        @DisplayName("A4: MASKED and FULL are both frozen, distinctly")
        void bothPrivacyLevelsAreFrozen() {
            assertThat(freeze("{\"reportPrivacy\": {\"policy\": \"MASKED\"}}")
                            .asIntegrationConfig().orElseThrow()
                            .reportPrivacy().policy())
                    .isEqualTo(ReportPrivacy.MASKED);
            assertThat(freeze("{\"reportPrivacy\": {\"policy\": \"FULL\"}}")
                            .asIntegrationConfig().orElseThrow()
                            .reportPrivacy().policy())
                    .isEqualTo(ReportPrivacy.FULL);
        }

        @Test
        @DisplayName("A5: the endpoint is preserved exactly - no trimming, rewriting or loss")
        void endpointIsPreservedExactly() {
            String endpoint = "https://example-a.test/webhook?tenant=7&path=/deep/value";

            CampaignConfigurationSnapshot snapshot = freeze("""
                    {
                      "webhook": {
                        "enabled": true,
                        "endpoint": "%s",
                        "events": ["campaign.attempt.cancelled"]
                      }
                    }
                    """.formatted(endpoint));

            assertThat(snapshot.asIntegrationConfig().orElseThrow().webhook().endpointOrNull())
                    .isEqualTo(endpoint);
        }
    }

    // === SNAP-IC-B: null and default are different facts ===

    @Nested
    @DisplayName("SNAP-IC-B: null and default semantics are deterministic")
    class NullAndDefaultSemantics {

        @Test
        @DisplayName("B1: an unconfigured campaign freezes to NULL, not to a synthesised default")
        void absentConfigurationStaysAbsent() {
            CampaignConfigurationSnapshot snapshot = freeze(null);

            // Absent at the storage layer ...
            assertThat(snapshot.getIntegrationConfig()).isNull();
            // ... and absent at the read layer. That is the point: the runtime is
            // told "never configured", which is a different claim from
            // "configured to enabled=false / FULL".
            assertThat(snapshot.asIntegrationConfig()).isEmpty();
        }

        @Test
        @DisplayName("B2: absent and explicitly-defaulted remain distinguishable")
        void absentIsNotCollapsedIntoTheDefault() {
            CampaignConfigurationSnapshot absent = freeze(null);
            CampaignConfigurationSnapshot explicitDefault = freeze(
                    "{\"webhook\": {\"enabled\": false}, \"reportPrivacy\": {\"policy\": \"FULL\"}}");

            assertThat(absent.getIntegrationConfig()).isNull();
            assertThat(explicitDefault.getIntegrationConfig()).isNotNull();
            assertThat(absent.asIntegrationConfig()).isEmpty();
            assertThat(explicitDefault.asIntegrationConfig()).isPresent();
            assertThat(absent.getIntegrationConfig())
                    .isNotEqualTo(explicitDefault.getIntegrationConfig());

            // Both describe inert behaviour today, yet only one records that an
            // operator actually chose it. Freezing must not erase that.
            CampaignIntegrationConfig defaults =
                    absent.asIntegrationConfig().orElse(CampaignIntegrationConfig.defaults());
            assertThat(defaults.isWebhookEnabled())
                    .isEqualTo(explicitDefault.asIntegrationConfig().orElseThrow()
                            .isWebhookEnabled());
        }

        @Test
        @DisplayName("B3: a JSON null payload is treated as absent, never as a default")
        void jsonNullIsTreatedAsAbsent() {
            CampaignConfigurationSnapshot snapshot = freeze("null");

            assertThat(snapshot.getIntegrationConfig()).isNull();
            assertThat(snapshot.asIntegrationConfig()).isEmpty();
        }

        @Test
        @DisplayName("B4: partial configuration keeps its validated defaults")
        void partialConfigurationIsCompletedByTheTypedModel() {
            // Only the webhook is configured; privacy is absent from the payload.
            CampaignConfigurationSnapshot snapshot = freeze("""
                    {
                      "webhook": {
                        "enabled": true,
                        "endpoint": "https://example-a.test/webhook",
                        "events": ["campaign.attempt.completed"]
                      }
                    }
                    """);

            CampaignIntegrationConfig frozen = snapshot.asIntegrationConfig().orElseThrow();
            assertThat(frozen.isWebhookEnabled()).isTrue();
            assertThat(frozen.reportPrivacy().policy())
                    .as("an omitted privacy block is the VB-7C.2 default")
                    .isEqualTo(ReportPrivacy.FULL);
        }

        @Test
        @DisplayName("B5: freezing is deterministic - the same campaign state yields the same snapshot")
        void freezingIsDeterministic() {
            String payload = """
                    {
                      "webhook": {
                        "enabled": true,
                        "endpoint": "https://example-a.test/webhook",
                        "events": ["campaign.attempt.completed", "campaign.attempt.failed"]
                      },
                      "reportPrivacy": { "policy": "MASKED" }
                    }
                    """;

            assertThat(freeze(payload).getIntegrationConfig())
                    .isEqualTo(freeze(payload).getIntegrationConfig());
        }
    }

    // === SNAP-IC-C: the frozen payload is canonical ===

    @Nested
    @DisplayName("SNAP-IC-C: the frozen payload is the canonical validated form")
    class Canonicalisation {

        @Test
        @DisplayName("C1: what is stored is what the platform understood, not what the client sent")
        void storedPayloadIsCanonicalised() {
            // Keys in an unusual order and "enabled" omitted. VB-7C.2's contract
            // resolves an omitted flag to disabled, and requires both an endpoint
            // and at least one event only when the webhook is enabled. What is
            // frozen must be that resolved interpretation, written out
            // explicitly, so a future consumer can never re-derive a different
            // meaning from the raw request.
            CampaignConfigurationSnapshot snapshot = freeze("""
                    {
                      "reportPrivacy": { "policy": "MASKED" },
                      "webhook": {
                        "endpoint": "https://example-a.test/webhook",
                        "events": ["campaign.attempt.failed", "campaign.attempt.completed"]
                      }
                    }
                    """);

            JsonNode stored = snapshot.getIntegrationConfig();
            assertThat(stored).isNotNull();
            assertThat(stored.get("webhook").get("enabled").asBoolean())
                    .as("the omitted flag is written out as its validated meaning")
                    .isFalse();
            assertThat(stored.get("webhook").get("endpoint").asText())
                    .isEqualTo("https://example-a.test/webhook");
            assertThat(stored.toString())
                    .as("the frozen text is the codec's canonical output")
                    .isEqualTo(snapshot.asIntegrationConfig().orElseThrow().toJson().toString());
        }

        @Test
        @DisplayName("C2: the frozen payload round-trips back to the same typed configuration")
        void frozenPayloadRoundTrips() {
            CampaignConfigurationSnapshot snapshot = freeze("""
                    {
                      "webhook": {
                        "enabled": true,
                        "endpoint": "https://example-a.test/webhook",
                        "events": ["campaign.attempt.cancelled"]
                      },
                      "reportPrivacy": { "policy": "MASKED" }
                    }
                    """);

            CampaignIntegrationConfig once = snapshot.asIntegrationConfig().orElseThrow();
            assertThat(CampaignIntegrationConfig.fromJson(once.toJson())).isEqualTo(once);
        }
    }

    // === SNAP-IC-D: the typed accessor is the only read path ===

    @Nested
    @DisplayName("SNAP-IC-D: the accessor fails loudly instead of inventing defaults")
    class AccessorDiscipline {

        @Test
        @DisplayName("D1: an unreadable frozen payload is a hard error, never a silent default")
        void unreadablePayloadIsAnError() {
            CampaignEntity campaign = campaignWith("""
                    {"webhook": {"enabled": true,
                                 "endpoint": "https://example-a.test/webhook",
                                 "events": ["campaign.attempt.completed"]}}""");
            CampaignConfigurationSnapshot snapshot = freezeCampaign(campaign);

            // Corrupt the frozen payload the way a bad direct DB write would.
            setRaw(snapshot, JSON.readTree("{\"webhook\": {\"enabled\": \"yes\"}}"));

            assertThatThrownBy(snapshot::asIntegrationConfig)
                    .as("the platform must not interpret its own snapshot by guessing")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Frozen integration configuration");
        }

        @Test
        @DisplayName("D2: the accessor returns the same value on every call")
        void accessorIsRepeatable() {
            CampaignConfigurationSnapshot snapshot = freeze("""
                    {"webhook": {"enabled": true,
                                 "endpoint": "https://example-a.test/webhook",
                                 "events": ["campaign.attempt.completed"]}}""");

            assertThat(snapshot.asIntegrationConfig().orElseThrow())
                    .isEqualTo(snapshot.asIntegrationConfig().orElseThrow());
        }
    }

    // === SNAP-IC-E: the runtime boundary is structural ===

    @Nested
    @DisplayName("SNAP-IC-E: execution-time configuration cannot come from a mutable campaign")
    class RuntimeBoundary {

        @Test
        @DisplayName("E1: the runtime config exposes the frozen integration configuration")
        void runtimeConfigExposesFrozenIntegrationConfig() {
            List<Method> accessors = Arrays.stream(
                            CampaignRuntimeConfigResolver.CampaignRuntimeConfig.class
                                    .getDeclaredMethods())
                    .filter(m -> m.getName().equals("asIntegrationConfig"))
                    .toList();

            assertThat(accessors).hasSize(1);
            assertThat(accessors.get(0).getReturnType()).isEqualTo(java.util.Optional.class);
        }

        @Test
        @DisplayName("E2: no API on the runtime config returns a mutable CampaignEntity")
        void runtimeConfigHasNoMutableCampaignAccessor() {
            assertThat(Arrays.stream(
                            CampaignRuntimeConfigResolver.CampaignRuntimeConfig.class
                                    .getDeclaredMethods())
                    .filter(m -> CampaignEntity.class
                            .isAssignableFrom(m.getReturnType()))
                    .map(Method::getName)
                    .toList())
                    .as("a runtime consumer must reach configuration only via the snapshot")
                    .isEmpty();
        }

        @Test
        @DisplayName("E3: no API on the snapshot returns a mutable CampaignEntity either")
        void snapshotHasNoMutableCampaignAccessor() {
            assertThat(Arrays.stream(CampaignConfigurationSnapshot.class.getDeclaredMethods())
                            .filter(m -> CampaignEntity.class
                                    .isAssignableFrom(m.getReturnType()))
                            .map(Method::getName)
                            .toList())
                    .isEmpty();
        }

        @Test
        @DisplayName("E4: no campaign field reaches execution through the snapshot's component list")
        void snapshotComponentsAreSnapshotOwned() {
            // The snapshot is a value holder, not a view: reading configuration
            // from it must not require the campaign row to be present, loadable,
            // or even still to exist.
            assertThat(Arrays.stream(CampaignConfigurationSnapshot.class.getDeclaredFields())
                            .filter(f -> !Modifier.isStatic(f.getModifiers()))
                            .map(Field::getType)
                            .filter(t -> CampaignEntity.class.isAssignableFrom(t))
                            .toList())
                    .isEmpty();
        }
    }

    // === SNAP-IC-F: no versioning was introduced ===

    @Nested
    @DisplayName("SNAP-IC-F: no versioning machinery came with this")
    class NoVersioning {

        @Test
        @DisplayName("F1: the snapshot carries no version, revision, sequence or generation field")
        void snapshotHasNoVersionFields() {
            assertThat(Arrays.stream(CampaignConfigurationSnapshot.class.getDeclaredFields())
                            .filter(f -> !f.isSynthetic())
                            .filter(f -> !Modifier.isStatic(f.getModifiers()))
                            .map(Field::getName)
                            .filter(n -> n.toLowerCase(Locale.ROOT)
                                    .matches(".*(version|revision|sequence|generation).*"))
                            .toList())
                    .as("snapshots are execution-owned, not campaign history")
                    .isEmpty();
        }

        @Test
        @DisplayName("F2: two executions from two campaign states differ by content, not by version")
        void executionsDifferByContentNotVersion() {
            CampaignConfigurationSnapshot first = freeze("""
                    {"webhook": {"enabled": true,
                                 "endpoint": "https://example-a.test/webhook",
                                 "events": ["campaign.attempt.completed"]},
                     "reportPrivacy": {"policy": "FULL"}}""");
            CampaignConfigurationSnapshot second = freeze("""
                    {"webhook": {"enabled": true,
                                 "endpoint": "https://example-b.test/webhook",
                                 "events": ["campaign.attempt.failed"]},
                     "reportPrivacy": {"policy": "MASKED"}}""");

            // They differ because their content differs. There is nothing to
            // order them by, and nothing needs to be.
            assertThat(first.getIntegrationConfig())
                    .isNotEqualTo(second.getIntegrationConfig());
            assertThat(first.asIntegrationConfig().orElseThrow().webhook().endpointOrNull())
                    .isEqualTo("https://example-a.test/webhook");
            assertThat(second.asIntegrationConfig().orElseThrow().webhook().endpointOrNull())
                    .isEqualTo("https://example-b.test/webhook");
        }
    }

    private static void setRaw(CampaignConfigurationSnapshot snapshot, JsonNode node) {
        try {
            Field f = CampaignConfigurationSnapshot.class.getDeclaredField("integrationConfig");
            f.setAccessible(true);
            f.set(snapshot, node);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
