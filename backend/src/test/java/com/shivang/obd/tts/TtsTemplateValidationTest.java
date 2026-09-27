package com.shivang.obd.tts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic TTS template contract: placeholder syntax, declared
 * variables, duplicate declarations, and stray-brace rejection.
 */
class TtsTemplateValidationTest {

    @Test
    void validSchemaAndTextPassAndExposeDeclaredNames() {
        List<TtsTemplateVariable> schema = List.of(
            new TtsTemplateVariable("firstName", "STRING", true),
            new TtsTemplateVariable("orderId", "STRING", true));

        java.util.Set<String> declared =
            TtsTemplateValidation.validateSchema(schema);

        assertThat(declared).containsExactlyInAnyOrder("firstName", "orderId");
        TtsTemplateValidation.validateTemplateText(
            "Hello {{firstName}}, your order {{ orderId }} is ready.", declared);
    }

    @Test
    void undeclaredVariableIsRejected() {
        java.util.Set<String> declared = TtsTemplateValidation.validateSchema(
            List.of(new TtsTemplateVariable("firstName", null, null)));

        assertThatThrownBy(() -> TtsTemplateValidation.validateTemplateText(
            "Hello {{lastName}}", declared))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("undeclared");
    }

    @Test
    void malformedPlaceholderIsRejected() {
        assertThatThrownBy(() -> TtsTemplateValidation.validateTemplateText(
            "Hello {{first-name}}", java.util.Set.of("firstname")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Malformed placeholder");

        assertThatThrownBy(() -> TtsTemplateValidation.validateTemplateText(
            "Hi { name } and {{ok}}", java.util.Set.of("ok")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stray braces");
    }

    @Test
    void duplicateDeclarationsAreRejected() {
        assertThatThrownBy(() -> TtsTemplateValidation.validateSchema(List.of(
            new TtsTemplateVariable("orderId", "STRING", true),
            new TtsTemplateVariable("orderId", "NUMBER", false))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate variable declaration");
    }

    @Test
    void invalidVariableNamesAreRejected() {
        assertThatThrownBy(() -> TtsTemplateValidation.validateSchema(
            List.of(new TtsTemplateVariable("1bad", null, null))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid");
    }
}
