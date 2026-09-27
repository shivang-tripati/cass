package com.shivang.obd.authz.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class OrganizationContextFilterTest {

    @RestController
    static class ProbeController {

        @GetMapping("/context-probe")
        String probe() {
            OrganizationContextHolder.setAuthenticated(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                null);
            return "set";
        }
    }

    @Test
    void contextIsClearedAfterRequestCompletes() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .addFilters(new OrganizationContextFilter())
            .build();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/context-probe")).andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("set");
        assertThat(OrganizationContextHolder.current()).isEmpty();
        assertThat(OrganizationContextHolder.currentUserId()).isEmpty();
        assertThat(OrganizationContextHolder.currentTenantId()).isEmpty();
    }

    @Test
    void contextRemainsEmptyWhenNeverSet() {
        assertThat(OrganizationContextHolder.current()).isEmpty();
    }
}
