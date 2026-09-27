package com.shivang.obd.voice.dtmf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * VB-2 configuration tests (spec §16.A): valid configuration, invalid
 * configuration, strictness (no silent normalization), bounds.
 */
class DtmfConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DtmfConfig parse(String json) {
        return DtmfConfig.fromTypeConfig(MAPPER.readTree(json));
    }

    @Nested
    class ValidConfiguration {

        @Test
        @DisplayName("single expected digit with defaults")
        void singleDigitDefaults() {
            DtmfConfig config = parse("{\"dtmf\": {\"expected\": \"1\"}}");

            assertThat(config.expected()).isEqualTo("1");
            assertThat(config.maxDigits()).isEqualTo(1);
            assertThat(config.getTerminator()).isEmpty();
            assertThat(config.timeoutSecs()).isEqualTo(DtmfConfig.DEFAULT_TIMEOUT_SECS);
        }

        @Test
        @DisplayName("sequence with explicit maxDigits, terminator and timeout")
        void sequenceFullConfig() {
            DtmfConfig config = parse(
                    "{\"dtmf\": {\"expected\": \"123\", \"maxDigits\": 8, \"terminator\": \"#\", \"timeoutSecs\": 15}}");

            assertThat(config.expected()).isEqualTo("123");
            assertThat(config.maxDigits()).isEqualTo(8);
            assertThat(config.getTerminator()).contains("#");
            assertThat(config.timeoutSecs()).isEqualTo(15);
        }

        @Test
        @DisplayName("* and # are accepted when explicitly configured")
        void starAndHashAllowed() {
            DtmfConfig config = parse("{\"dtmf\": {\"expected\": \"*#\"}}");

            assertThat(config.expected()).isEqualTo("*#");
        }

        @Test
        @DisplayName("explicit nulls for optional fields fall back to defaults")
        void explicitNullsFallBackToDefaults() {
            DtmfConfig config = parse(
                    "{\"dtmf\": {\"expected\": \"1\", \"terminator\": null, \"timeoutSecs\": null, \"maxDigits\": null}}");

            assertThat(config.getTerminator()).isEmpty();
            assertThat(config.timeoutSecs()).isEqualTo(DtmfConfig.DEFAULT_TIMEOUT_SECS);
            assertThat(config.maxDigits()).isEqualTo(1);
        }
    }

    @Nested
    class InvalidConfiguration {

        @Test
        @DisplayName("missing type_config is rejected")
        void missingTypeConfig() {
            assertThatThrownBy(() -> DtmfConfig.fromTypeConfig(null))
                    .isInstanceOf(DtmfConfigInvalidException.class);
        }

        @Test
        @DisplayName("missing dtmf payload is rejected")
        void missingDtmfPayload() {
            assertThatThrownBy(() -> parse("{\"integration\": {}}"))
                    .isInstanceOf(DtmfConfigInvalidException.class)
                    .hasMessageContaining("dtmf");
        }

        @Test
        @DisplayName("missing expected is rejected")
        void missingExpected() {
            assertThatThrownBy(() -> parse("{\"dtmf\": {\"timeoutSecs\": 5}}"))
                    .isInstanceOf(DtmfConfigInvalidException.class)
                    .hasMessageContaining("expected");
        }

        @Test
        @DisplayName("blank expected is rejected")
        void blankExpected() {
            assertThatThrownBy(() -> parse("{\"dtmf\": {\"expected\": \"   \"}}"))
                    .isInstanceOf(DtmfConfigInvalidException.class);
        }

        @Test
        @DisplayName("letters in expected are rejected — never silently normalized")
        void lettersRejected() {
            assertThatThrownBy(() -> parse("{\"dtmf\": {\"expected\": \"1a2\"}}"))
                    .isInstanceOf(DtmfConfigInvalidException.class)
                    .hasMessageContaining("a");
        }

        @Test
        @DisplayName("expected longer than the sequence cap is rejected")
        void tooLongRejected() {
            assertThatThrownBy(() -> parse("{\"dtmf\": {\"expected\": \"12345678901234567\"}}"))
                    .isInstanceOf(DtmfConfigInvalidException.class)
                    .hasMessageContaining("16");
        }

        @Test
        @DisplayName("maxDigits below the expected length is rejected")
        void maxDigitsBelowExpectedRejected() {
            assertThatThrownBy(() -> parse("{\"dtmf\": {\"expected\": \"123\", \"maxDigits\": 2}}"))
                    .isInstanceOf(DtmfConfigInvalidException.class)
                    .hasMessageContaining("maxDigits");
        }

        @Test
        @DisplayName("non-integer maxDigits is rejected")
        void nonIntegerMaxDigitsRejected() {
            assertThatThrownBy(() -> parse("{\"dtmf\": {\"expected\": \"1\", \"maxDigits\": 2.5}}"))
                    .isInstanceOf(DtmfConfigInvalidException.class);
        }

        @Test
        @DisplayName("multi-character terminator is rejected")
        void multiCharTerminatorRejected() {
            assertThatThrownBy(() -> parse("{\"dtmf\": {\"expected\": \"1\", \"terminator\": \"##\"}}"))
                    .isInstanceOf(DtmfConfigInvalidException.class)
                    .hasMessageContaining("terminator");
        }

        @Test
        @DisplayName("non-digit terminator is rejected")
        void nonDigitTerminatorRejected() {
            assertThatThrownBy(() -> parse("{\"dtmf\": {\"expected\": \"1\", \"terminator\": \"A\"}}"))
                    .isInstanceOf(DtmfConfigInvalidException.class);
        }

        @Test
        @DisplayName("timeout below 1s and above 120s is rejected")
        void timeoutBoundsRejected() {
            assertThatThrownBy(() -> parse("{\"dtmf\": {\"expected\": \"1\", \"timeoutSecs\": 0}}"))
                    .isInstanceOf(DtmfConfigInvalidException.class);
            assertThatThrownBy(() -> parse("{\"dtmf\": {\"expected\": \"1\", \"timeoutSecs\": 121}}"))
                    .isInstanceOf(DtmfConfigInvalidException.class);
        }
    }
}
