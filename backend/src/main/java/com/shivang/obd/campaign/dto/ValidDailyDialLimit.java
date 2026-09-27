package com.shivang.obd.campaign.dto;

import com.shivang.obd.campaign.DailyDialLimitService;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validator side of the composed {@code @DailyDialLimit} rule — kept in
 * the dto package so the annotation's {@code validatedBy} reference
 * resolves without a dependency cycle; the rule itself (bounds, constant)
 * lives in the campaign domain ({@link DailyDialLimitService}
 * {@code MAX_VOICE_BLAST_DAILY_DIAL_LIMIT}).
 */
@Constraint(validatedBy = ValidDailyDialLimit.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDailyDialLimit {

    String message() default "dailyDialLimit must be between 1 and "
            + DailyDialLimitService.MAX_VOICE_BLAST_DAILY_DIAL_LIMIT
            + ", or omitted for the platform maximum";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Null-safe: null is valid (platform default semantics). */
    class Validator implements ConstraintValidator<ValidDailyDialLimit, Integer> {

        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null
                    || (value >= 1
                        && value <= DailyDialLimitService.MAX_VOICE_BLAST_DAILY_DIAL_LIMIT);
        }
    }
}
