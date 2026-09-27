package com.shivang.obd.campaign;

/**
 * Retry strategy applied by the future execution engine. Only the fixed
 * interval model is required today; additional strategies must not be
 * added without a product requirement.
 */
public enum RetryStrategy {
    FIXED
}
