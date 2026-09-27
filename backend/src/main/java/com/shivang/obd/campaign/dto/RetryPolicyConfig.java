package com.shivang.obd.campaign.dto;

import com.shivang.obd.campaign.RetryStrategy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Fixed retry policy configuration. attempts = 0 disables retries
 * (interval must then be omitted); attempts > 0 requires a positive
 * interval. Upper bounds are deliberate product limits.
 */
public record RetryPolicyConfig(
    @NotNull @Min(0) @Max(10) Integer maxAttempts,
    @Min(1) @Max(604800) Integer intervalSeconds,
    RetryStrategy strategy
) {
}
