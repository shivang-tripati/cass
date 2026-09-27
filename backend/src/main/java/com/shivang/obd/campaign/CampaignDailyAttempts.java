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
 * The single canonical domain rule for the campaign daily attempt ceiling
 * (VB-6D.3): null (platform default) or 1..{@link
 * DailyAttemptSafetyService#MAX_DAILY_ATTEMPTS_PER_CONTACT}, where the upper
 * bound is the platform safety maximum and comes from one named constant — no
 * magic numbers anywhere else.
 *
 * <p>One rule, three boundaries, never duplicated:
 * <ol>
 *   <li>this constraint on the campaign create/update DTOs (REST → 400);</li>
 *   <li>{@link DailyAttemptSafetyService#assertConfigurable(Integer)} on the
 *       service path, for entities built outside REST;</li>
 *   <li>the {@code ck_campaigns_max_daily_attempts} /
 *       {@code ck_cec_max_daily_attempts} CHECK constraints (V51).</li>
 * </ol>
 */
@Constraint(validatedBy = CampaignDailyAttempts.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CampaignDailyAttempts {

    String message() default "maxDailyAttempts must be between "
            + DailyAttemptSafetyService.MIN_DAILY_ATTEMPTS_PER_CONTACT
            + " and " + DailyAttemptSafetyService.MAX_DAILY_ATTEMPTS_PER_CONTACT
            + ", or omitted for the platform default";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Null-safe: null is valid and means "use the platform default". */
    class Validator
            implements ConstraintValidator<CampaignDailyAttempts, Integer> {

        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null
                    || (value >= DailyAttemptSafetyService.MIN_DAILY_ATTEMPTS_PER_CONTACT
                        && value <= DailyAttemptSafetyService.MAX_DAILY_ATTEMPTS_PER_CONTACT);
        }
    }
}
