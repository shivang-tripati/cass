package com.shivang.obd.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.security.config.JwtSecurityBeans;
import com.shivang.obd.security.config.RestAuthErrorHandling;
import com.shivang.obd.security.config.SecurityConfig;
import com.shivang.obd.security.token.JwtProperties;
import com.shivang.obd.security.token.NimbusJwtTokenService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(controllers = SecurityProbeController.class)
@Import({SecurityConfig.class, JwtSecurityBeans.class, RestAuthErrorHandling.class, SecurityCurrentUserProvider.class})
@org.springframework.test.context.ActiveProfiles("security-web-slice")
@TestPropertySource(properties = {
    "obd.security.jwt.secret=unit-test-secret-key-that-is-long-enough-32!",
    "obd.security.jwt.issuer=unit-test",
    "obd.security.jwt.access-token-expiration-seconds=900"
})
class SecuritySliceTest {

    private static final String TEST_SECRET = "unit-test-secret-key-that-is-long-enough-32!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProperties jwtProperties;

    private static final UUID TEST_SESSION = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private String tokenFor(UUID userId) {
        NimbusJwtTokenService tokenService =
            new NimbusJwtTokenService(new JwtProperties(TEST_SECRET, "unit-test", 900, 604800));
        return tokenService.issueAccessToken(
                new AuthenticatedUser(userId, "probe@example.com", TEST_SESSION))
            .tokenValue();
    }

    @AfterEach
    void cleanContextThreadLocal() {
        OrganizationContextHolder.clear();
    }

    @Test
    void unauthenticatedRequestIsRejectedWithProblemDetail() throws Exception {
        mockMvc.perform(get("/security-probe"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void validTokenAuthenticatesAndPopulatesCurrentUserAbstractions() throws Exception {
        UUID userId = UUID.fromString("7b6c9a10-0000-4000-8000-00000000abcd");
        MvcResult result = mockMvc.perform(get("/security-probe")
                .header("Authorization", "Bearer " + tokenFor(userId)))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString())
            .contains("userId=" + userId)
            .contains("sessionId=" + TEST_SESSION)
            .doesNotContain("tenantId=non-null");
        assertThat(OrganizationContextHolder.current()).isEmpty();
    }

    @Test
    void spoofedOrganizationHeadersAreNeverTrusted() throws Exception {
        mockMvc.perform(get("/security-probe")
                .header("X-Tenant-ID", "99999999-9999-4999-8999-999999999999")
                .header("X-Reseller-ID", "88888888-8888-4888-8888-888888888888"))
            .andExpect(status().isUnauthorized());

        assertThat(OrganizationContextHolder.current()).isEmpty();
        assertThat(OrganizationContextHolder.currentTenantId()).isEmpty();
    }

    @Test
    void currentUserProviderIsEmptyOutsideAuthentication() {
        SecurityCurrentUserProvider provider = new SecurityCurrentUserProvider();
        assertThat(provider.current()).isEmpty();
    }

    @Test
    void refreshEndpointIsReachableWithoutAccessToken() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth/refresh"))
            .andExpect(status().isOk());
    }

    @Test
    void logoutIsReachableWithoutAccessTokenSoExpiredSessionsCanEndThemselves() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth/logout"))
            .andExpect(status().isOk());
    }

    @Test
    void opaqueRefreshTokenIsNotAcceptedAsBearerCredential() throws Exception {
        mockMvc.perform(get("/security-probe")
                .header("Authorization", "Bearer some-opaque-refresh-token-value"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void meEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void spoofedIdentityHeadersNeverAuthenticateMe() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                .header("X-User-ID", "99999999-9999-4999-8999-999999999999")
                .header("X-Email", "attacker@obd.test"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void changePasswordRequiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth/change-password"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIsRejectedEverywhere() throws Exception {
        String tampered = tokenFor(UUID.fromString("7b6c9a10-0000-4000-8000-00000000abcd")) + "x";
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tampered))
            .andExpect(status().isUnauthorized());
    }
}
