package com.shivang.obd.security.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClientIpResolverTest {

    private HttpServletRequest request(String remoteAddr, String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }

    private ClientIpResolver direct() {
        return new ClientIpResolver(new TrustedProxyProperties(
            TrustedProxyProperties.Mode.DIRECT, java.util.List.of()));
    }

    private ClientIpResolver trusted(java.util.List<String> cidrs) {
        return new ClientIpResolver(new TrustedProxyProperties(
            TrustedProxyProperties.Mode.TRUSTED_PROXY, cidrs));
    }

    @Test
    void directModeAlwaysUsesRemoteAddr() {
        assertThat(direct().resolve(request("198.51.100.9", "1.2.3.4")))
            .isEqualTo("198.51.100.9");
    }

    @Test
    void spoofedForwardedHeaderFromUntrustedClientIsIgnored() {
        var resolver = trusted(java.util.List.of("10.0.0.0/8"));
        // remote is NOT a trusted proxy: XFF must be ignored entirely.
        assertThat(resolver.resolve(request("203.0.113.7", "1.2.3.4")))
            .isEqualTo("203.0.113.7");
    }

    @Test
    void validForwardedIpFromTrustedProxyIsSelected() {
        var resolver = trusted(java.util.List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.0.0.5", "198.51.100.23")))
            .isEqualTo("198.51.100.23");
    }

    @Test
    void multipleForwardedAddressesSelectRightmostNonTrusted() {
        var resolver = trusted(java.util.List.of("10.0.0.0/8", "127.0.0.1"));
        assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.1, 10.0.0.6, 10.0.0.7")))
            .isEqualTo("203.0.113.1");
        // All-internal chain except the immediate client hop:
        assertThat(resolver.resolve(request("10.0.0.5", "192.0.2.9, 10.0.0.6")))
            .isEqualTo("192.0.2.9");
    }

    @Test
    void missingOrBlankForwardedHeaderFallsBackToRemoteAddr() {
        var resolver = trusted(java.util.List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.0.0.5", null))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "   "))).isEqualTo("10.0.0.5");
    }

    @Test
    void ipv6LoopbackTrustedProxyWithIpv6Client() {
        var resolver = trusted(java.util.List.of("0:0:0:0:0:0:0:1/128", "::1/128"));
        assertThat(resolver.resolve(request("::1", "2001:db8::42")))
            .isEqualTo("2001:db8::42");
    }

    @Test
    void unparseableForwardedEntriesAreSkippedSafely() {
        var resolver = trusted(java.util.List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.0.0.5", "not-an-ip, 198.51.100.77")))
            .isEqualTo("198.51.100.77");
    }

    @Nested
    class CidrMatching {

        private final ClientIpResolver resolver =
            trusted(java.util.List.of("10.0.0.0/8", "192.168.1.55"));

        @Test
        void cidrRangeMatches() {
            assertThat(resolver.isTrustedProxy("10.255.1.2")).isTrue();
            assertThat(resolver.isTrustedProxy("11.0.0.1")).isFalse();
        }

        @Test
        void bareAddressMatchesExactHost() {
            assertThat(resolver.isTrustedProxy("192.168.1.55")).isTrue();
            assertThat(resolver.isTrustedProxy("192.168.1.56")).isFalse();
        }
    }
}
