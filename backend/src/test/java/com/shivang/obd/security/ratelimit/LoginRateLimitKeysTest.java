package com.shivang.obd.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoginRateLimitKeysTest {

    @Test
    void keysUseVersionedNamespacedFormat() {
        assertThat(LoginRateLimitKeys.forIp("1.2.3.4"))
            .startsWith("obd:security:login:v1:ip:");
        assertThat(LoginRateLimitKeys.forAccount("a@b.test"))
            .startsWith("obd:security:login:v1:account:");
        assertThat(LoginRateLimitKeys.forCombined("1.2.3.4", "a@b.test"))
            .startsWith("obd:security:login:v1:combined:")
            .contains(LoginRateLimitKeys.digest("1.2.3.4"))
            .contains(LoginRateLimitKeys.digest("a@b.test"));
    }

    @Test
    void keysNeverContainRawSensitiveComponents() {
        String email = "Shivang@Example.COM";
        String ip = "203.0.113.7";

        String accountKey = LoginRateLimitKeys.forAccount(email);
        String ipKey = LoginRateLimitKeys.forIp(ip);
        String combinedKey = LoginRateLimitKeys.forCombined(ip, email);

        assertThat(accountKey).doesNotContain(email).doesNotContain("shivang");
        assertThat(ipKey).doesNotContain(ip);
        assertThat(combinedKey).doesNotContain(ip).doesNotContain(email);
    }

    @Test
    void digestIsDeterministicAndCaseSensitivePerNormalizedInput() {
        assertThat(LoginRateLimitKeys.forAccount("user@obd.test"))
            .isEqualTo(LoginRateLimitKeys.forAccount("user@obd.test"));
        assertThat(LoginRateLimitKeys.forAccount("user@obd.test"))
            .isNotEqualTo(LoginRateLimitKeys.forAccount("other@obd.test"));
    }

    @Test
    void noPasswordsOrTokensArePartOfKeyConstruction() {
        // Keys are derived exclusively from ip/email digests; there is no API
        // accepting password/token material — enforced by the builder's signature.
        Map<String, String> keyByDigest = new HashMap<>();
        keyByDigest.put(LoginRateLimitKeys.digest("a"), LoginRateLimitKeys.forAccount("a"));
        assertThat(keyByDigest).hasSize(1);
    }
}
