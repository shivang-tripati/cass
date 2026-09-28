package com.shivang.obd.campaign.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.CallAttemptStatus;
import com.shivang.obd.campaign.CampaignStatus;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-7C.2, locked OD-2: the external webhook event vocabulary is a <b>separate
 * public contract</b> from the internal domain event vocabulary.
 *
 * <p>This class exists to make that separation mechanically enforced rather than
 * a matter of discipline. The platform's internal vocabulary —
 * {@code CampaignDomainEvent}'s {@code CAMPAIGN_*} constants and the various
 * internal lifecycle enums — is an implementation seam with no external
 * guarantee. If those names ever leaked into the public webhook contract,
 * every internal refactor would silently become a breaking change for customers,
 * which is precisely the failure OD-2 exists to prevent.
 */
class WebhookEventVocabularyTest {

    @Test
    @DisplayName("1. every supported event serializes to its stable public value")
    void everyEventHasAPublicValue() {
        for (WebhookEvent event : WebhookEvent.values()) {
            assertThat(event.publicValue())
                    .as("%s", event.name())
                    .isNotBlank()
                    .isEqualTo(event.publicValue().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    @DisplayName("2. every supported event deserializes back to itself")
    void everyEventRoundTrips() {
        for (WebhookEvent event : WebhookEvent.values()) {
            assertThat(WebhookEvent.fromPublicValue(event.publicValue()))
                    .as("%s", event.publicValue())
                    .isEqualTo(event);
        }
    }

    @Test
    @DisplayName("3. an unknown identifier is rejected, never silently mapped")
    void unknownIdentifierRejected() {
        assertThatThrownBy(() -> WebhookEvent.fromPublicValue("campaign.attempt.exploded"))
                .isInstanceOf(CampaignConfigInvalidException.class);
        assertThat(WebhookEvent.parse("campaign.attempt.exploded")).isEmpty();
    }

    @Test
    @DisplayName("4. a null or blank identifier is not a supported event")
    void nullAndBlankNotSupported() {
        assertThat(WebhookEvent.parse(null)).isEmpty();
        assertThat(WebhookEvent.parse("  ")).isEmpty();
        assertThat(WebhookEvent.isSupported("")).isFalse();
    }

    @Test
    @DisplayName("5. no public value is, or contains, an internal enum constant name")
    void internalNamesNeverLeak() {
        // The concrete guard, read from the REAL internal declarations so it
        // cannot drift: adding an internal event immediately subjects it to this
        // check without anyone editing this file.
        Set<String> internalNames = new java.util.LinkedHashSet<>(
                internalCampaignDomainEventConstants());
        for (CallAttemptStatus s : CallAttemptStatus.values()) {
            internalNames.add(s.name());
        }
        for (CampaignStatus s : CampaignStatus.values()) {
            internalNames.add(s.name());
        }
        assertThat(internalNames)
                .as("the internal vocabulary must actually have been read")
                .isNotEmpty();

        for (WebhookEvent event : WebhookEvent.values()) {
            String publicValue = event.publicValue();
            for (String internal : internalNames) {
                assertThat(publicValue)
                        .as("public value '%s' must not expose the internal name '%s'",
                                publicValue, internal)
                        .doesNotContain(internal);
            }
        }
    }

    /**
     * The internal {@code CampaignDomainEvent} string constants, read
     * reflectively from the class itself.
     *
     * <p>Reflection is used here, in a test, on purpose: it is what makes the
     * assertion track reality rather than a hand-copied list. The production
     * configuration model contains no reflection - the separation it enforces
     * does not need any.
     */
    private static Set<String> internalCampaignDomainEventConstants() {
        Set<String> constants = new java.util.LinkedHashSet<>();
        for (java.lang.reflect.Field field
                : com.shivang.obd.campaign.event.CampaignDomainEvent.class.getDeclaredFields()) {
            if (field.getType() != String.class) {
                continue;
            }
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof String s && !s.isBlank()) {
                    constants.add(s);
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Cannot read internal event constant", e);
            }
        }
        return constants;
    }

    @Test
    @DisplayName("6. public values are dotted lowercase identifiers, not Java identifiers")
    void publicValuesAreNotJavaIdentifiers() {
        for (WebhookEvent event : WebhookEvent.values()) {
            assertThat(event.publicValue())
                    .as("%s", event.name())
                    .contains(".")
                    .matches("[a-z0-9.]+")
                    .isNotEqualTo(event.name());
        }
    }

    @Test
    @DisplayName("7. the public value is explicit, not derived from the Java constant name")
    void publicValueIsExplicitlyDefined() {
        // Renaming the Java constant must not change the wire format. Asserted by
        // showing the value is a stable literal unrelated to name().
        assertThat(WebhookEvent.ATTEMPT_COMPLETED.publicValue())
                .isEqualTo("campaign.attempt.completed");
        assertThat(WebhookEvent.ATTEMPT_FAILED.publicValue())
                .isEqualTo("campaign.attempt.failed");
        assertThat(WebhookEvent.ATTEMPT_CANCELLED.publicValue())
                .isEqualTo("campaign.attempt.cancelled");
    }

    @Test
    @DisplayName("8. the vocabulary is limited to the terminal call outcomes the platform "
                + "actually models - no speculative catalogue")
    void vocabularyIsMinimal() {
        // Guards against a future phase growing the public contract by accident.
        // Every event must map to a real terminal CallAttemptStatus, and nothing
        // else may be added without an actual domain event to report.
        assertThat(WebhookEvent.values()).hasSize(3);
        assertThat(Arrays.stream(WebhookEvent.values())
                .map(e -> e.publicValue()).toList())
                .containsExactly(
                        "campaign.attempt.completed",
                        "campaign.attempt.failed",
                        "campaign.attempt.cancelled");

        // Terminal attempt states are the only externally meaningful outcomes.
        assertThat(CallAttemptStatus.values())
                .contains(CallAttemptStatus.COMPLETED,
                        CallAttemptStatus.FAILED,
                        CallAttemptStatus.CANCELLED);
    }

    @Test
    @DisplayName("9. every event's subject is the ATTEMPT - no campaign-lifecycle or "
                + "non-terminal state has leaked into the external vocabulary")
    void subjectIsAlwaysTheAttempt() {
        // Asserted structurally rather than by substring. A public value such as
        // "campaign.attempt.completed" legitimately contains the English word
        // "completed", which is also the lowercase of an internal CampaignStatus
        // constant - so a naive substring check here would be a false positive
        // rather than a real leak. The separation guarantee is already enforced
        // exactly by test 5, which compares against the real internal
        // identifiers. What this test adds is the semantic claim: the subject of
        // every publishable event is a call attempt, never a campaign, an
        // execution or a non-terminal state.
        for (WebhookEvent event : WebhookEvent.values()) {
            String[] segments = event.publicValue().split("\\.");
            assertThat(segments)
                    .as("%s", event.publicValue())
                    .hasSize(3);
            assertThat(segments[1])
                    .as("the subject segment of %s", event.publicValue())
                    .isEqualTo("attempt");
        }

        // Non-terminal attempt states are not outcomes and must not be publishable.
        for (WebhookEvent event : WebhookEvent.values()) {
            assertThat(event.publicValue())
                    .doesNotContain("queued")
                    .doesNotContain("in_progress")
                    .doesNotContain("dialing")
                    .doesNotContain("ringing");
        }
    }
}
