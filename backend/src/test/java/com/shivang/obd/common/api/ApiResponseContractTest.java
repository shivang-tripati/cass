package com.shivang.obd.common.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

class ApiResponseContractTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestApiController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new RequestIdFilter())
            .build();
    }

    @Test
    void singleObjectResponseHasSuccessDataMetaAndNoMessage() throws Exception {
        mockMvc.perform(get("/test/object"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value("1"))
            .andExpect(jsonPath("$.data.name").value("first"))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty())
            .andExpect(jsonPath("$.meta.timestamp").isNotEmpty());
    }

    @Test
    void listResponseWrapsArrayInData() throws Exception {
        mockMvc.perform(get("/test/list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.pagination").doesNotExist());
    }

    @Test
    void paginatedResponseExposesOffsetMetadata() throws Exception {
        mockMvc.perform(get("/test/page"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.pagination.page").value(2))
            .andExpect(jsonPath("$.pagination.size").value(20))
            .andExpect(jsonPath("$.pagination.totalElements").value(245))
            .andExpect(jsonPath("$.pagination.totalPages").value(13))
            .andExpect(jsonPath("$.pagination.hasNext").value(true))
            .andExpect(jsonPath("$.pagination.hasPrevious").value(true));
    }

    @Test
    void createdResourceReturnsHttp201() throws Exception {
        mockMvc.perform(post("/test/created"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value("9"));
    }

    @Test
    void validatedRequestBodySucceeds() throws Exception {
        mockMvc.perform(post("/test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@test.com\",\"password\":\"password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
