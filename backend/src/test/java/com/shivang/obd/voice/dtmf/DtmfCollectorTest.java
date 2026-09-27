package com.shivang.obd.voice.dtmf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * VB-2 input-rule tests (spec §16.C/D): single-digit expected/unexpected,
 * sequence correct/incorrect/partial/max-limit, terminator, unsupported
 * digits. Pure classification — no persistence involved.
 */
class DtmfCollectorTest {

    private static final DtmfConfig SINGLE =
            new DtmfConfig("1", 1, null, 10, "TERMINATE");
    private static final DtmfConfig SEQUENCE =
            new DtmfConfig("123", 3, null, 10, "TERMINATE");
    private static final DtmfConfig SEQUENCE_WITH_TERMINATOR =
            new DtmfConfig("123", 8, "#", 10, "TERMINATE");

    @Nested
    class SingleDigit {

        @Test
        @DisplayName("expected digit → VALID")
        void expectedDigitValid() {
            DtmfCollector.FeedResult result = DtmfCollector.feed(SINGLE, "", '1');

            assertThat(result.terminal()).isTrue();
            assertThat(result.result()).isEqualTo(DtmfResultType.VALID);
            assertThat(result.collected()).isEqualTo("1");
        }

        @Test
        @DisplayName("unexpected digit → INVALID (terminal)")
        void unexpectedDigitInvalid() {
            DtmfCollector.FeedResult result = DtmfCollector.feed(SINGLE, "", '2');

            assertThat(result.terminal()).isTrue();
            assertThat(result.result()).isEqualTo(DtmfResultType.INVALID);
        }
    }

    @Nested
    class Sequences {

        @Test
        @DisplayName("1,2,3 → VALID on the third digit")
        void correctSequenceValid() {
            DtmfCollector.FeedResult first = DtmfCollector.feed(SEQUENCE, "", '1');
            assertThat(first.terminal()).isFalse();

            DtmfCollector.FeedResult second = DtmfCollector.feed(SEQUENCE, first.collected(), '2');
            assertThat(second.terminal()).isFalse();

            DtmfCollector.FeedResult third = DtmfCollector.feed(SEQUENCE, second.collected(), '3');
            assertThat(third.terminal()).isTrue();
            assertThat(third.result()).isEqualTo(DtmfResultType.VALID);
            assertThat(third.collected()).isEqualTo("123");
        }

        @Test
        @DisplayName("1,2,4 → INVALID")
        void wrongDigitInvalid() {
            String partial = DtmfCollector.feed(SEQUENCE, "", '1').collected();
            partial = DtmfCollector.feed(SEQUENCE, partial, '2').collected();

            DtmfCollector.FeedResult result = DtmfCollector.feed(SEQUENCE, partial, '4');

            assertThat(result.terminal()).isTrue();
            assertThat(result.result()).isEqualTo(DtmfResultType.INVALID);
        }

        @Test
        @DisplayName("wrong first digit is terminal immediately")
        void wrongFirstDigitTerminal() {
            DtmfCollector.FeedResult result = DtmfCollector.feed(SEQUENCE, "", '9');

            assertThat(result.terminal()).isTrue();
            assertThat(result.result()).isEqualTo(DtmfResultType.INVALID);
        }

        @Test
        @DisplayName("maxDigits cap → over-input is terminal INVALID")
        void overInputInvalid() {
            DtmfConfig capped = new DtmfConfig("1234", 3, null, 10, "TERMINATE");
            String partial = DtmfCollector.feed(capped, "", '1').collected();
            partial = DtmfCollector.feed(capped, partial, '2').collected();
            partial = DtmfCollector.feed(capped, partial, '3').collected();
            assertThat(partial).hasSize(3);

            DtmfCollector.FeedResult result = DtmfCollector.feed(capped, partial, '4');

            assertThat(result.terminal()).isTrue();
            assertThat(result.result()).isEqualTo(DtmfResultType.INVALID);
            assertThat(result.reason()).contains("Maximum digit count");
        }
    }

    @Nested
    class Terminator {

        @Test
        @DisplayName("terminator with matching input → VALID")
        void terminatorMatchingInputValid() {
            String partial = DtmfCollector.feed(SEQUENCE_WITH_TERMINATOR, "", '1').collected();
            partial = DtmfCollector.feed(SEQUENCE_WITH_TERMINATOR, partial, '2').collected();
            partial = DtmfCollector.feed(SEQUENCE_WITH_TERMINATOR, partial, '3').collected();

            DtmfCollector.FeedResult result =
                    DtmfCollector.feed(SEQUENCE_WITH_TERMINATOR, partial, '#');

            assertThat(result.terminal()).isTrue();
            assertThat(result.result()).isEqualTo(DtmfResultType.VALID);
        }

        @Test
        @DisplayName("terminator with mismatched input → INVALID")
        void terminatorMismatchedInputInvalid() {
            String partial = DtmfCollector.feed(SEQUENCE_WITH_TERMINATOR, "", '1').collected();

            DtmfCollector.FeedResult result =
                    DtmfCollector.feed(SEQUENCE_WITH_TERMINATOR, partial, '#');

            assertThat(result.terminal()).isTrue();
            assertThat(result.result()).isEqualTo(DtmfResultType.INVALID);
        }

        @Test
        @DisplayName("terminator with empty input → INVALID")
        void terminatorEmptyInputInvalid() {
            DtmfCollector.FeedResult result =
                    DtmfCollector.feed(SEQUENCE_WITH_TERMINATOR, "", '#');

            assertThat(result.result()).isEqualTo(DtmfResultType.INVALID);
        }
    }

    @Nested
    class UnsupportedInput {

        @Test
        @DisplayName("unsupported character is terminal INVALID, never normalized")
        void unsupportedCharTerminal() {
            DtmfCollector.FeedResult result = DtmfCollector.feed(SINGLE, "", 'A');

            assertThat(result.terminal()).isTrue();
            assertThat(result.result()).isEqualTo(DtmfResultType.INVALID);
            assertThat(result.reason()).contains("Unsupported");
        }
    }

    @Nested
    class Evaluate {

        @Test
        @DisplayName("timeout evaluation with exact input → VALID")
        void timeoutExactInputValid() {
            DtmfCollector.EvaluateResult result =
                    DtmfCollector.evaluate(SEQUENCE, "123", "timeout");

            assertThat(result.result()).isEqualTo(DtmfResultType.VALID);
        }

        @Test
        @DisplayName("timeout evaluation with partial input → INVALID")
        void timeoutPartialInputInvalid() {
            DtmfCollector.EvaluateResult result =
                    DtmfCollector.evaluate(SEQUENCE, "12", "timeout");

            assertThat(result.result()).isEqualTo(DtmfResultType.INVALID);
        }

        @Test
        @DisplayName("timeout evaluation with no input → INVALID")
        void timeoutNoInputInvalid() {
            DtmfCollector.EvaluateResult result =
                    DtmfCollector.evaluate(SINGLE, "", "timeout");

            assertThat(result.result()).isEqualTo(DtmfResultType.INVALID);
        }
    }
}
