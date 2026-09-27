package com.shivang.obd.campaign.dto;

import com.shivang.obd.campaign.RetryStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Campaign retry policy configuration.
 *
 * <p><b>Two layers, one object.</b> The flat fields
 * ({@code maxAttempts}/{@code intervalSeconds}/{@code strategy}) are the
 * original single-allowance model and remain the default for every category.
 * {@code rules} is the optional VB-6D.2 per-category layer: when present, a
 * matching rule takes precedence for its category; a category with no rule
 * falls back to the flat allowance. A campaign that omits {@code rules}
 * therefore behaves exactly as it did before this phase.
 *
 * <p>{@code maxAttempts} counts retries, so the maximum number of attempts is
 * {@code 1 + maxAttempts}.
 */
@Schema(name = "RetryPolicyConfig",
        description = "Campaign retry policy. The flat fields are a single default "
                + "allowance applied to every retryable failure category; the optional "
                + "per-category rules override it for their own category. Permanent "
                + "failures are never retried regardless of this configuration.")
public record RetryPolicyConfig(

        @NotNull @Min(0) @Max(10)
        @Schema(description = "Default number of RETRIES permitted beyond the initial "
                + "attempt (0-10) for any category without its own rule. Not the same as "
                + "total attempts: maximum total attempts = 1 + maxAttempts, so 0 means no "
                + "retries and 2 yields at most 3 attempts.",
                minimum = "0", maximum = "10", example = "2")
        Integer maxAttempts,

        @Min(1) @Max(5999)
        @Schema(description = "Default delay in seconds before the next retry. Required "
                + "when maxAttempts > 0. Prefer the per-category MM:SS rules for new "
                + "configurations; this field is retained for the original flat model.",
                minimum = "1", maximum = "5999", example = "300")
        Integer intervalSeconds,

        @Schema(description = "Retry timing strategy. Only FIXED is supported; "
                + "additional strategies require a product requirement and are rejected "
                + "by the database constraint.",
                allowableValues = {"FIXED"}, example = "FIXED")
        RetryStrategy strategy,

        @Schema(description = "Optional per-category rules. When empty or omitted, the "
                + "flat fields above govern entirely. At most one rule per category; a "
                + "duplicate category is rejected.")
        List<@Valid RetryRuleConfig> rules
) {

    /**
     * The pre-VB-6D.2 shape, so existing call sites and tests keep their exact
     * previous meaning.
     */
    public RetryPolicyConfig(Integer maxAttempts, Integer intervalSeconds, RetryStrategy strategy) {
        this(maxAttempts, intervalSeconds, strategy, null);
    }
}
