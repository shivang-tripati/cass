package com.shivang.obd.campaign;

import com.shivang.obd.campaign.dto.ValidDailyDialLimit;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Canonical domain validation rule for the Voice Blast campaign daily
 * dial limit (VB-6C.2): null (platform default) or 1..3, where 3 is
 * {@link DailyDialLimitService#MAX_VOICE_BLAST_DAILY_DIAL_LIMIT} — the
 * single named constant; no magic numbers anywhere else.
 * <p>
 * Applied as a composed constraint on the campaign DTOs, and enforced by
 * the service layer ({@code CampaignService.validateConfiguration}) for
 * entities constructed outside REST, and by the V48 database CHECK as the
 * last line of defense. One rule, three boundaries — never duplicated
 * logic.
 */
@Constraint(validatedBy = ValidDailyDialLimit.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface DailyDialLimit {

    String message() default "dailyDialLimit must be between 1 and "
            + DailyDialLimitService.MAX_VOICE_BLAST_DAILY_DIAL_LIMIT
            + ", or omitted for the platform maximum";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Null-safe checker: null is valid (platform default semantics). */
    class Validator implements jakarta.validation.ConstraintValidator<ValidDailyDialLimit, Integer> {

        @Override
        public boolean isValid(Integer value, jakarta.validation.ConstraintValidatorContext context) {
            return value == null
                    || (value >= 1
                        && value <= DailyDialLimitService.MAX_VOICE_BLAST_DAILY_DIAL_LIMIT);
        }
    }
}
