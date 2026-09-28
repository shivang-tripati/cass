package com.shivang.obd.campaign.config;

import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Report privacy policy (VB-7C.2, locked OD-10).
 *
 * <h2>Configuration only</h2>
 *
 * <p>This enum describes what a future reporting/export subsystem should do with
 * contact identifiers. <b>Nothing consumes it yet.</b> VB-7C.2 explicitly does not
 * change the existing attempt-listing APIs or their current contact-data
 * visibility, does not add report queries, does not generate reports and does
 * not implement exports. A campaign configured with {@link #MASKED} today still
 * exposes contact data through the attempt endpoints exactly as it did before,
 * because those endpoints do not read this configuration.
 *
 * <h2>Why only two values</h2>
 *
 * <p>The repository does not define a report privacy model, so this phase does
 * not invent one. Two things <em>are</em> established, and the two values map
 * exactly onto them:
 *
 * <ul>
 *   <li><b>{@link #FULL}</b> — report nothing more than the platform exposes
 *       today. This is the default because OD-10 requires the current
 *       contact-data visibility semantics to be unchanged; a default of
 *       {@code MASKED} would be a silent behaviour change for every existing
 *       campaign.</li>
 *   <li><b>{@link #MASKED}</b> — show only the last four digits of a contact
 *       number. That is not an invented convention: it is the platform's
 *       <em>only</em> existing masking semantic, used by
 *       {@code EslClient.maskNumber} ("Masks a phone number for logging (shows
 *       only the last 4 digits)"), {@code NoOpAgentLegDialer.mask} and the
 *       various {@code maskUuid} helpers. Reusing it keeps report masking
 *       consistent with log masking rather than introducing a second one.</li>
 * </ul>
 *
 * <p>Levels an operator might reasonably ask for next — aggregation,
 * pseudonymisation, hashing — are <b>deliberately not defined</b>, because
 * nothing in the repository defines their semantics and guessing would create an
 * unsupported contractual promise. That is recorded as an open product decision
 * in the VB-7C.2 report.
 *
 * <h2>Relationship to report authorization</h2>
 *
 * <p>Unrelated and deliberately untouched. {@code V1} seeds {@code REPORT_VIEW}
 * and {@code REPORT_EXPORT} capabilities (and a {@code REPORT_VIEWER} role), but
 * no reporting subsystem exists and this phase does not build one, change those
 * capabilities, or wire them to anything. Whether a caller may see a report is an
 * authorization question; how much of a contact number a report contains is a
 * privacy question. They are separate.
 */
@io.swagger.v3.oas.annotations.media.Schema(
    description = "How much of a contact identifier a future report or export may contain. "
        + "Configuration only: it does not change the existing attempt-listing APIs. "
        + "FULL is the default and matches current platform behaviour; MASKED shows only "
        + "the last four digits.")
@JsonDeserialize(using = CampaignIntegrationJson.PrivacyPolicyDeserializer.class)
@JsonSerialize(using = CampaignIntegrationJson.PrivacyPolicySerializer.class)
public enum ReportPrivacy {

    /** Reports may include complete contact identifiers. The default. */
    @com.fasterxml.jackson.annotation.JsonProperty("FULL")
    FULL("FULL"),

    /** Reports may show only the last four digits of a contact number. */
    @com.fasterxml.jackson.annotation.JsonProperty("MASKED")
    MASKED("MASKED");

    private final String value;

    ReportPrivacy(String value) {
        this.value = value;
    }

    /** The stable public value this policy serializes to. */
    public String value() {
        return value;
    }

    /** Resolves a public value, or empty when unsupported. */
    public static Optional<ReportPrivacy> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(p -> p.value.equals(raw) || p.value.equals(raw.toUpperCase(Locale.ROOT)))
                .findFirst();
    }

    /**
     * Resolves a public value.
     *
     * @throws CampaignConfigInvalidException when the value is not supported
     */
    public static ReportPrivacy require(String raw) {
        return parse(raw).orElseThrow(() -> new CampaignConfigInvalidException(
                "'" + raw + "' is not a supported report privacy policy. Supported: "
                        + supportedValues() + "."));
    }

    /** The supported values, for error messages and generated documentation. */
    public static String supportedValues() {
        StringBuilder sb = new StringBuilder();
        for (ReportPrivacy p : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p.value);
        }
        return sb.toString();
    }
}
