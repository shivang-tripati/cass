package com.shivang.obd.common.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shivang.obd.common.api.response.RequestIdFilter;
import com.shivang.obd.common.api.scaffold.TestApiController;
import com.shivang.obd.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ErrorResponseContractTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestApiController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new RequestIdFilter())
            .build();
    }

    @Test
    void invalidRequestBodyReturnsProblemDetailWithFieldErrors() throws Exception {
        mockMvc.perform(post("/test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"\",\"password\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Validation error"))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.timestamp").isNotEmpty())
            .andExpect(jsonPath("$.errors[?(@.field=='email')].code", hasItem("NOT_BLANK")))
            .andExpect(jsonPath("$.errors[?(@.field=='password')].code", hasItem("SIZE")))
            .andExpect(jsonPath("$.errors[?(@.field=='email')].message", hasItem(org.hamcrest.Matchers.notNullValue())));
    }

    @Test
    void malformedJsonBodyReturnsBadRequestProblem() throws Exception {
        mockMvc.perform(post("/test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.detail").value("Malformed request body."));
    }

    @Test
    void typeMismatchReturnsBadRequestProblem() throws Exception {
        mockMvc.perform(get("/test/type-mismatch").param("id", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.detail", containsString("id")));
    }

    @Test
    void missingParameterReturnsBadRequestProblem() throws Exception {
        mockMvc.perform(get("/test/type-mismatch"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.detail", containsString("id")));
    }

    @Test
    void businessNotFoundReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/not-found"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.title").value("Resource not found"))
            .andExpect(jsonPath("$.detail").value("Requested resource not found"));
    }

    @Test
    void conflictReturns409ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/conflict"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFLICT"))
            .andExpect(jsonPath("$.detail").value("Resource state conflicts with current operation"));
    }

    @Test
    void authenticationFailureReturns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/unauthorized"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void accessDeniedReturns403ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/forbidden"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void unsupportedMethodReturns405ProblemDetail() throws Exception {
        mockMvc.perform(delete("/test/health"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.title").value("Method not allowed"));
    }

    @Test
    void unexpectedExceptionReturnsSafe500WithoutInternalDetails() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(containsString("INTERNAL_SERVER_ERROR")))
            .andExpect(content().string(containsString("An unexpected error occurred.")))
            .andExpect(content().string(not(containsString("IllegalStateException"))))
            .andExpect(content().string(not(containsString("hunter2"))))
            .andExpect(content().string(not(containsString("jdbc"))))
            .andExpect(content().string(not(containsString("secret-db"))));
    }
}
