package com.shivang.obd.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Campaign-owned retry policy value object. Policy only — the future
 * execution engine owns applying it to failed call attempts. Always
 * present and normalized: attempts 0 means "no retries" with a null
 * interval; strategy defaults to FIXED.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Embeddable
public class RetryPolicySpec {

    @Column(name = "retry_max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "retry_interval_seconds")
    private Integer intervalSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "retry_strategy", nullable = false, length = 20)
    private RetryStrategy strategy;
}
