package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.campaign.dto.CreateCampaignRequest;
import com.shivang.obd.campaign.dto.RetryPolicyConfig;
import com.shivang.obd.campaign.dto.ScheduleConfig;
import com.shivang.obd.campaign.dto.UpdateCampaignRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VB-6C.2 — API-boundary (jakarta bean validation) coverage for
 * {@code dailyDialLimit}. The service-level guard is proven in
 * {@code CampaignDailyDialLimitServiceTest}; the REST status/authorization
 * contract in {@code CampaignApiSliceTest}. This suite closes the remaining
 * boundary: that the composed {@link DailyDialLimit} constraint is actually
 * <em>applied</em> by a real {@link Validator} to the create/update request
 * DTOs, and that a violation maps onto the platform's existing 400
 * {@code VALIDATION_ERROR} ProblemDetail contract.
 * <p>
 * This is the check that would have caught a constraint annotation wired to a
 * validator typed for a different annotation (which Hibernate Validator does
 * not accept as a substitute) - the rule must genuinely reject out-of-range
 * input here, not only further down in the service.
 */
class CampaignDailyDialLimitValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidatorFactory() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        if (factory != null) {
            factory.close();
        }
    }

    /** A request that is valid in every respect except possibly the limit. */
    private CreateCampaignRequest createRequest(Integer dailyDialLimit) {
        return new CreateCampaignRequest(
                "Voice blast", null, CampaignType.PLAYFILE, CampaignRunMode.ONE_TIME,
                null, null,
                ContentMode.AUDIO, UUID.randomUUID(), null,
                new ScheduleConfig(LocalDate.of(2026, 9, 1),
                        LocalTime.of(10, 0), LocalTime.of(18, 0), "Asia/Kolkata",
                        Set.of(), null),
                new RetryPolicyConfig(0, null, null),
                null, null, false,
                dailyDialLimit);
    }

    private UpdateCampaignRequest updateRequest(Integer dailyDialLimit) {
        return new UpdateCampaignRequest(
                "Voice blast", null, CampaignRunMode.ONE_TIME,
                null, null,
                ContentMode.AUDIO, UUID.randomUUID(), null,
                new ScheduleConfig(LocalDate.of(2026, 9, 1),
                        LocalTime.of(10, 0), LocalTime.of(18, 0), "Asia/Kolkata",
                        Set.of(), null),
                new RetryPolicyConfig(0, null, null),
                null, null,
                dailyDialLimit);
    }

    private Set<ConstraintViolation<CreateCampaignRequest>> createViolations(
            Integer dailyDialLimit) {
        return validator.validate(createRequest(dailyDialLimit));
    }

    private Set<ConstraintViolation<UpdateCampaignRequest>> updateViolations(
            Integer dailyDialLimit) {
        return validator.validate(updateRequest(dailyDialLimit));
    }

    @Nested
    class CreateRequest {

        @Test
        @DisplayName("VAL-C1: null (omitted) is valid — platform default")
        void nullIsValid() {
            assertThat(createViolations(null)).isEmpty();
        }

        @ParameterizedTest(name = "VAL-C2: {0} is valid")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("VAL-C2: 1, 2, 3 are valid")
        void validBoundsAccepted(int value) {
            assertThat(createViolations(value)).isEmpty();
        }

        @ParameterizedTest(name = "VAL-C3: {0} is rejected")
        @ValueSource(ints = {0, -1, 4, 100})
        @DisplayName("VAL-C3: 0, -1, 4, 100 are rejected")
        void outOfRangeRejected(int value) {
            assertThat(createViolations(value))
                    .isNotEmpty()
                    .anySatisfy(violation -> {
                        assertThat(violation.getPropertyPath().toString())
                                .isEqualTo("dailyDialLimit");
                        assertThat(violation.getMessage())
                                .contains("dailyDialLimit")
                                .contains("1 and "
                                        + DailyDialLimitService
                                                .MAX_VOICE_BLAST_DAILY_DIAL_LIMIT);
                    });
        }

        @Test
        @DisplayName("VAL-C4: the bound is the shared constant, not a literal")
        void boundComesFromCanonicalConstant() {
            int max = DailyDialLimitService.MAX_VOICE_BLAST_DAILY_DIAL_LIMIT;
            assertThat(max).isEqualTo(3);
            assertThat(createViolations(max)).isEmpty();
            assertThat(createViolations(max + 1)).isNotEmpty();
            assertThat(createViolations(0)).isNotEmpty();
        }
    }

    @Nested
    class UpdateRequest {

        @Test
        @DisplayName("VAL-U1: null (omitted) is valid — clears the explicit limit")
        void nullIsValid() {
            assertThat(updateViolations(null)).isEmpty();
        }

        @ParameterizedTest(name = "VAL-U2: {0} is valid")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("VAL-U2: 1, 2, 3 are valid")
        void validBoundsAccepted(int value) {
            assertThat(updateViolations(value)).isEmpty();
        }

        @ParameterizedTest(name = "VAL-U3: {0} is rejected")
        @ValueSource(ints = {0, -1, 4, 100})
        @DisplayName("VAL-U3: 0, -1, 4, 100 are rejected")
        void outOfRangeRejected(int value) {
            assertThat(updateViolations(value))
                    .isNotEmpty()
                    .anySatisfy(violation -> assertThat(
                            violation.getPropertyPath().toString())
                            .isEqualTo("dailyDialLimit"));
        }
    }

    @Test
    @DisplayName("VAL-X1: a violation maps onto the existing 400 VALIDATION_ERROR contract")
    void violationMapsToExistingErrorContract() {
        Set<ConstraintViolation<CreateCampaignRequest>> violations = createViolations(4);
        assertThat(violations).isNotEmpty();

        var handler = new com.shivang.obd.common.exception.GlobalExceptionHandler();
        var response = handler.handleConstraintViolations(
                new jakarta.validation.ConstraintViolationException(violations));

        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getProperties().get("code"))
                .isEqualTo("VALIDATION_ERROR");
        @SuppressWarnings("unchecked")
        var errors = (java.util.List<com.shivang.obd.common.api.error.FieldError>)
                response.getBody().getProperties().get("errors");
        assertThat(errors).extracting(
                        com.shivang.obd.common.api.error.FieldError::field)
                .contains("dailyDialLimit");
    }
}
