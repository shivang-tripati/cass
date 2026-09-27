package com.shivang.obd.campaign.dto;

import com.shivang.obd.campaign.RetryRuleCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * One campaign-configured retry rule (VB-6D.2).
 *
 * <p>{@code maxRetries} counts <em>retries</em>, not attempts: {@code 2} means
 * an initial attempt plus two retries, i.e. at most three attempts.
 *
 * <p>{@code retryDelay} uses the product {@code MM:SS} form and is validated
 * by pattern here and by range in the domain layer, so a malformed or
 * out-of-range delay is rejected at the boundary rather than reaching the dial
 * path.
 */
@Schema(name = "RetryRuleConfig",
        description = "Per-category retry rule. Applies only to Voice Blast "
                + "outcomes; a campaign can restrict retries but can never make a "
                + "permanent failure retryable.")
public record RetryRuleConfig(

        @NotNull
        @Schema(description = "Failure category this rule governs. "
                + "NO_ANSWER/BUSY/HANGUP/FAILED are reachable today. "
                + "SWITCHED_OFF and NOT_REACHABLE are accepted for a future reliable "
                + "provider mapping but no provider outcome currently feeds them; such "
                + "causes arrive as HANGUP and are governed by the HANGUP rule.",
                allowableValues = {"NO_ANSWER", "BUSY", "HANGUP", "FAILED",
                        "SWITCHED_OFF", "NOT_REACHABLE"},
                example = "NO_ANSWER")
        RetryRuleCategory category,

        @Schema(description = "Whether retries are permitted for this category. "
                + "Omit or set false to disable retries here. A rule can only restrict "
                + "retries, never enable one for a permanent failure.",
                example = "true")
        Boolean enabled,

        @NotNull @Min(0) @Max(10)
        @Schema(description = "Number of RETRIES permitted beyond the initial attempt "
                + "(0-10). Not the same as total attempts: maxRetries=2 yields at most "
                + "3 attempts. A retry is consumed only by an attempt that actually "
                + "placed a call — queue re-processing, capacity rejection and "
                + "compliance rejection never consume it.",
                minimum = "0", maximum = "10", example = "2")
        Integer maxRetries,

        @Pattern(regexp = "^\\d{2}:\\d{2}$",
                message = "retryDelay must use the MM:SS format (e.g. \"05:00\")")
        @Schema(description = "Delay before the next retry, as MM:SS. Minutes 00-99, "
                + "seconds 00-59, minimum 00:01. Required when the rule is enabled and "
                + "permits at least one retry; omit it when the rule is disabled or "
                + "maxRetries is 0, because a delay on such a rule could never take "
                + "effect. Measured from the moment the attempt failed and is "
                + "timezone-independent; clamping into the campaign's calling hours is "
                + "applied separately from the execution snapshot.",
                pattern = "^\\d{2}:\\d{2}$", example = "05:00")
        String retryDelay
) {
}
