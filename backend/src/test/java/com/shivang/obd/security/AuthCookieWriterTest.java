package com.shivang.obd.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.security.token.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthCookieWriterTest {

    private static final String SECRET = "unit-test-secret-key-that-is-long-enough-32!";

    private MockHttpServletResponse response;
    private AuthCookieWriter writer;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        response = new MockHttpServletResponse();
        jwtProperties = new JwtProperties(SECRET, "unit-test", 900, 604800);
        writer = new AuthCookieWriter(new AuthCookieProperties(false, null), jwtProperties);
    }

    @Test
    void refreshTokenCookieIsHttpOnlySameSiteStrictAndPathScoped() {
        writer.addRefreshToken(response, "raw-refresh-value");

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("obd_rt=raw-refresh-value");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Strict");
        assertThat(setCookie).contains("Path=/api/v1/auth");
        // Cookie lifetime mirrors the server-side refresh TTL exactly.
        assertThat(setCookie).contains("Max-Age=604800");
    }

    @Test
    void secureFlagFollowsProperties() {
        AuthCookieWriter secureWriter =
            new AuthCookieWriter(new AuthCookieProperties(true, null), jwtProperties);
        secureWriter.addRefreshToken(response, "r");

        assertThat(response.getHeader("Set-Cookie")).contains("Secure");
    }

    @Test
    void insecureProfileOmitsSecureFlagForLocalHttpDevelopment() {
        writer.addRefreshToken(response, "r");

        assertThat(response.getHeader("Set-Cookie")).doesNotContain("Secure");
    }

    @Test
    void domainIsAppliedOnlyWhenConfigured() {
        AuthCookieWriter domainWriter =
            new AuthCookieWriter(new AuthCookieProperties(true, "example.com"), jwtProperties);
        MockHttpServletResponse blankDomainResponse = new MockHttpServletResponse();
        AuthCookieWriter blankDomainWriter =
            new AuthCookieWriter(new AuthCookieProperties(true, "  "), jwtProperties);

        domainWriter.clearRefreshToken(blankDomainResponse);
        writer.addRefreshToken(response, "r");

        assertThat(blankDomainResponse.getHeader("Set-Cookie")).contains("Domain=example.com");
        assertThat(response.getHeader("Set-Cookie")).doesNotContain("Domain=");
    }

    @Test
    void clearExpiresTheCookieImmediatelyWithEmptyValue() {
        writer.clearRefreshToken(response);

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("obd_rt=");
        assertThat(setCookie).contains("Max-Age=0");
        assertThat(setCookie).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth");
    }
}
