package com.shivang.obd.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FieldErrorMappingTest {

    private final com.shivang.obd.common.exception.GlobalExceptionHandler handler =
        new com.shivang.obd.common.exception.GlobalExceptionHandler();

    static class Sample {

        @NotBlank
        private String name;
    }

    @Test
    void constraintViolationMapsToStableUpperSnakeCodeAndLeafField() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<Sample>> violations = validator.validate(new Sample());
        assertThat(violations).isNotEmpty();

        var response = handler.handleConstraintViolations(new jakarta.validation.ConstraintViolationException(violations));

        assertEquals(400, response.getBody().getStatus());
        assertEquals("VALIDATION_ERROR", response.getBody().getProperties().get("code"));
        @SuppressWarnings("unchecked")
        var errors = (java.util.List<com.shivang.obd.common.api.error.FieldError>) response.getBody().getProperties().get("errors");
        assertThat(errors).hasSize(1);
        assertEquals("name", errors.get(0).field());
        assertEquals("NOT_BLANK", errors.get(0).code());
        assertThat(errors.get(0).message()).isNotBlank();
    }
}
