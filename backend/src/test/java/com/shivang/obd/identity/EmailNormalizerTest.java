package com.shivang.obd.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailNormalizerTest {

    @Test
    void trimsAndLowercases() {
        assertThat(EmailNormalizer.canonicalize("  Shivang@Example.COM "))
            .isEqualTo("shivang@example.com");
    }

    @Test
    void alreadyNormalizedIsStable() {
        String once = EmailNormalizer.canonicalize("User@OBD.io");
        assertThat(EmailNormalizer.canonicalize(once)).isEqualTo(once);
    }

    @Test
    void nullMapsToNull() {
        assertThat(EmailNormalizer.canonicalize(null)).isNull();
    }
}
