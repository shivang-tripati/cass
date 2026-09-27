package com.shivang.obd.campaign;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The single canonical domain rule for the maximum call duration (VB-6E):
 * null (platform default of 300s) or
 * 1..{@link MaxCallDurationPolicy#MAX_MAX_CALL_DURATION_SECONDS}, where the
 * bounds come from one named authority — no magic numbers anywhere else.
 *
 * <p>One rule, three boundaries, never duplicated, exactly as
 * {@link CampaignDailyAttempts} and {@link DailyDialLimit} are:
 * <ol>
 *   <li>this constraint on the campaign create/update DTOs (REST to 400);</li>
 *   <li>{@link MaxCallDurationPolicy#assertConfigurable(Integer)} on the
 *       service path, for entities built outside REST;</li>
 *   <li>the {@code ck_campaigns_max_call_duration} /
 *       {@code ck_cec_max_call_duration} CHECK constraints (V52).</li>
 * </ol>
 */
@Constraint(validatedBy = MaxCallDurationSeconds.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxCallDurationSeconds {

    String message() default "maxCallDurationSeconds must be between "
            + MaxCallDurationPolicy.MIN_MAX_CALL_DURATION_SECONDS
            + " and " + MaxCallDurationPolicy.MAX_MAX_CALL_DURATION_SECONDS
            + ", or omitted for the platform default of "
            + MaxCallDurationPolicy.DEFAULT_MAX_CALL_DURATION_SECONDS;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Null-safe: null is valid and means "use the platform default". */
    class Validator
            implements ConstraintValidator<MaxCallDurationSeconds, Integer> {

        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null
                    || (value >= MaxCallDurationPolicy.MIN_MAX_CALL_DURATION_SECONDS
                        && value <= MaxCallDurationPolicy.MAX_MAX_CALL_DURATION_SECONDS);
        }
    }
}
