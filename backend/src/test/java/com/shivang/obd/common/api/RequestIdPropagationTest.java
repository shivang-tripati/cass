package com.shivang.obd.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shivang.obd.common.api.response.RequestIdFilter;
import com.shivang.obd.common.api.scaffold.TestApiController;
import com.shivang.obd.common.exception.GlobalExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RequestIdPropagationTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestApiController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new RequestIdFilter())
            .build();
    }

    @Test
    void generatesRequestIdWhenAbsentAndEchoesItInHeaderAndMeta() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/object"))
            .andExpect(status().isOk())
            .andReturn();

        String headerId = result.getResponse().getHeader("X-Request-ID");
        String body = result.getResponse().getContentAsString();

        assertThat(headerId).isNotBlank();
        assertThat(UUID.fromString(headerId)).isNotNull();
        assertThat(body).contains("\"requestId\":\"" + headerId + "\"");
        assertThat(body).doesNotContain("\"traceId\"");
    }

    @Test
    void preservesClientSuppliedRequestIdEndToEnd() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/object").header("X-Request-ID", "client-correlation-123"))
            .andExpect(status().isOk())
            .andExpect(result1 -> { })
            .andReturn();

        String headerId = result.getResponse().getHeader("X-Request-ID");
        String body = result.getResponse().getContentAsString();

        assertThat(headerId).isEqualTo("client-correlation-123");
        assertThat(body).contains("\"requestId\":\"client-correlation-123\"");
    }
}
