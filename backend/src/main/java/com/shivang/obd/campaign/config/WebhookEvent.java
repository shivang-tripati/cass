package com.shivang.obd.campaign.config;

import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The <b>public</b> webhook event vocabulary (VB-7C.2, locked OD-2).
 *
 * <h2>This is not the internal domain event vocabulary</h2>
 *
 * <p>The platform has an internal event vocabulary — {@code CampaignDomainEvent}
 * with constants such as {@code CAMPAIGN_CREATED} — published in-process through
 * Spring's {@code ApplicationEventPublisher} to <b>zero</b> listeners. That
 * vocabulary is an implementation seam, not a contract. Binding an external
 * integration to it would make every internal refactor a breaking API change, and
 * its record carries no contact, attempt or outcome information at all.
 *
 * <p>This enum is therefore a <b>separate</b> vocabulary. The two are never
 * conflated: a {@code WebhookEvent} is not a {@code CampaignDomainEvent}, and
 * {@link #publicValue()} is a stable dotted identifier rather than a Java
 * constant name. {@code WebhookEventVocabularyIsolationTest} asserts that no
 * serialized value equals any internal enum constant, so the separation cannot
 * silently erode.
 *
 * <h2>Deliberately small</h2>
 *
 * <p>Only the three terminal call-outcome facts are defined, because those are
 * the only externally meaningful outcomes the repository actually models today
 * ({@code CallAttemptStatus.COMPLETED / FAILED / CANCELLED}). Non-terminal
 * states ({@code QUEUED}, {@code IN_PROGRESS}) are not outcomes, and no
 * execution-, DTMF-, IVR-, agent- or connectivity-level event is defined,
 * because <b>no such domain event exists to report</b>. Inventing a large
 * catalogue here would advertise a contract the platform cannot honour.
 *
 * <h2>No delivery is implemented</h2>
 *
 * <p><b>Selecting an event here does not mean it is delivered.</b> VB-7C.2 is a
 * configuration-only phase: there is no delivery transport, no producer, no
 * queue, no signer and no retry. These identifiers exist so a customer can
 * record intent, and so the future delivery subsystem has a stable public
 * contract to implement. Runtime event production and delivery are explicitly
 * out of scope and are a future phase.
 */
@io.swagger.v3.oas.annotations.media.Schema(
    description = "A public webhook event identifier. This is a separate PUBLIC contract "
        + "from the platform's internal domain events, whose names are never exposed. "
        + "The documented values are the Java constants' @JsonProperty names, not their "
        + "identifiers. Selecting an event records configured intent only: VB-7C.2 "
        + "implements no delivery, so no event is emitted or sent yet.")
@JsonDeserialize(using = CampaignIntegrationJson.EventDeserializer.class)
@JsonSerialize(using = CampaignIntegrationJson.EventSerializer.class)
public enum WebhookEvent {

    /** A call attempt finished successfully. */
    @com.fasterxml.jackson.annotation.JsonProperty("campaign.attempt.completed")
    ATTEMPT_COMPLETED("campaign.attempt.completed"),

    /** A call attempt ended in failure. */
    @com.fasterxml.jackson.annotation.JsonProperty("campaign.attempt.failed")
    ATTEMPT_FAILED("campaign.attempt.failed"),

    /** A call attempt was cancelled before or during processing. */
    @com.fasterxml.jackson.annotation.JsonProperty("campaign.attempt.cancelled")
    ATTEMPT_CANCELLED("campaign.attempt.cancelled");

    private final String publicValue;

    WebhookEvent(String publicValue) {
        this.publicValue = publicValue;
    }

    /**
     * The stable, public identifier this event serializes to.
     *
     * <p>This exact string is the API contract. It is defined explicitly rather
     * than derived from {@link #name()} so that renaming the Java constant for
     * internal reasons can never silently break a customer's stored
     * configuration.
     */
    public String publicValue() {
        return publicValue;
    }

    /**
     * Resolves a public identifier to its event.
     *
     * @throws CampaignConfigInvalidException if the identifier is not one this
     *         platform publishes — an unknown event is never silently discarded
     */
    public static WebhookEvent fromPublicValue(String value) {
        return parse(value).orElseThrow(() -> new CampaignConfigInvalidException(
                "'" + value + "' is not a supported webhook event. Supported: "
                        + supportedValues()));
    }

    /** Resolves a public identifier, or empty when it is not supported. */
    public static Optional<WebhookEvent> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(e -> e.publicValue.equals(value))
                .findFirst();
    }

    /** Whether a public identifier names a supported event. */
    public static boolean isSupported(String value) {
        return parse(value).isPresent();
    }

    /** The supported identifiers, in declaration order, for error messages. */
    public static String supportedValues() {
        StringBuilder sb = new StringBuilder();
        for (WebhookEvent e : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.publicValue);
        }
        return sb.toString();
    }

    /**
     * Resolves an ordered, duplicate-free, fully-supported set of public
     * identifiers.
     *
     * <p>Rejects (rather than normalises) a duplicate: a selection listing the
     * same event twice is a client mistake, and silently collapsing it would
     * hide that mistake behind a value the client never sent.
     *
     * @throws CampaignConfigInvalidException if any identifier is unsupported or
     *         repeated
     */
    public static Set<WebhookEvent> resolveAll(Iterable<String> publicValues) {
        Set<WebhookEvent> resolved = new LinkedHashSet<>();
        if (publicValues == null) {
            return resolved;
        }
        for (String raw : publicValues) {
            WebhookEvent event = fromPublicValue(raw);
            if (!resolved.add(event)) {
                throw new CampaignConfigInvalidException(
                        "integrationConfig.webhook.events contains the duplicate event '"
                                + event.publicValue + "'; select each event at most once.");
            }
        }
        return resolved;
    }
}
