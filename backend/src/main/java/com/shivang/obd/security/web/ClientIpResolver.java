package com.shivang.obd.security.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Single source of truth for client IP resolution.
 *
 * <p>Fail-closed trust model: forwarding headers are considered only when
 * the direct peer (remoteAddr) is within a configured trusted proxy CIDR.
 * From a multi-hop X-Forwarded-For chain the rightmost non-trusted address
 * is selected. Spoofed chains from untrusted clients are ignored entirely.</p>
 */
@Component
@EnableConfigurationProperties(TrustedProxyProperties.class)
public class ClientIpResolver {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final TrustedProxyProperties properties;

    public ClientIpResolver(TrustedProxyProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (properties.mode() != TrustedProxyProperties.Mode.TRUSTED_PROXY) {
            return remoteAddr;
        }
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String forwarded = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }
        return rightmostUntrusted(forwarded);
    }

    private String rightmostUntrusted(String forwardedFor) {
        String[] candidates = forwardedFor.split(",");
        for (int i = candidates.length - 1; i >= 0; i--) {
            String candidate = candidates[i].trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (!isTrustedProxy(candidate)) {
                return candidate;
            }
        }
        // Every claimed address is itself a trusted proxy: fall back to the
        // peer we actually received the request from.
        return null;
    }

    boolean isTrustedProxy(String address) {
        for (String cidr : properties.trustedProxies()) {
            if (matchesCidr(address, cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesCidr(String address, String cidr) {
        int slash = cidr.indexOf('/');
        String network = slash > -1 ? cidr.substring(0, slash) : cidr;
        int prefix = slash > -1 ? Integer.parseInt(cidr.substring(slash + 1)) : -1;
        try {
            byte[] addrBytes = InetAddress.getByName(address).getAddress();
            byte[] netBytes = InetAddress.getByName(network).getAddress();
            if (addrBytes.length != netBytes.length) {
                return false;
            }
            if (prefix < 0) {
                prefix = netBytes.length * 8;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes && i < addrBytes.length; i++) {
                if (addrBytes[i] != netBytes[i]) {
                    return false;
                }
            }
            int remainderBits = prefix % 8;
            if (remainderBits > 0 && fullBytes < addrBytes.length) {
                int mask = 0xFF00 >> remainderBits;
                if ((addrBytes[fullBytes] & mask) != (netBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException ex) {
            return false;
        }
    }
}
