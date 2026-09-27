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
 * The single canonical domain validation rule for the Voice Blast campaign
 * daily dial limit (VB-6C.2): null (platform default) or 1..{@value
 * DailyDialLimitService#MAX_VOICE_BLAST_DAILY_DIAL_LIMIT}, where the bound
 * comes from {@link DailyDialLimitService#MAX_VOICE_BLAST_DAILY_DIAL_LIMIT}
 * — the one named constant. No magic numbers anywhere else.
 * <p>
 * One rule, three boundaries, never duplicated logic:
 * <ol>
 *   <li>this constraint, on the campaign create/update DTOs (REST input
 *       boundary → 400 through the existing ProblemDetail contract);</li>
 *   <li>{@link DailyDialLimitService#assertConfigurable(Integer)}, called by
 *       {@code CampaignService.create/update} for entities constructed
 *       outside REST;</li>
 *   <li>the {@code ck_campaigns_daily_dial_limit} / {@code
 *       ck_cec_daily_dial_limit} database CHECK constraints (V48), the last
 *       line of defense.</li>
 * </ol>
 * <p>
 * The validator is nested here and correctly typed for
 * <em>this</em> annotation, so Hibernate Validator actually applies the rule
 * (a validator typed for a different constraint annotation is not a
 * substitute for this one).
 */
@Constraint(validatedBy = DailyDialLimit.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface DailyDialLimit {

    String message() default "dailyDialLimit must be between 1 and "
            + DailyDialLimitService.MAX_VOICE_BLAST_DAILY_DIAL_LIMIT
            + ", or omitted for the platform maximum";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Null-safe checker: null is valid (platform default semantics). */
    class Validator implements ConstraintValidator<DailyDialLimit, Integer> {

        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null
                    || (value >= 1
                        && value <= DailyDialLimitService.MAX_VOICE_BLAST_DAILY_DIAL_LIMIT);
        }
    }
}
