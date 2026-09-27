package com.shivang.obd.tts;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic TTS template validation: placeholder syntax, variable
 * naming, duplicate declarations, and text/declaration agreement. Purely
 * structural — no expression language, no scripting, no rendering.
 */
public final class TtsTemplateValidation {

    /** Allowed placeholder form: {{name}} with optional inner whitespace. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(.*?)\\s*}}");

    /** Any single '{' or '}' not participating in a valid {{...}} pair is malformed. */
    private static final Pattern STRAY_BRACE = Pattern.compile("\\{|\\}");

    /** Variable names: letters/digits/underscore, starting with a letter. */
    private static final Pattern VARIABLE_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,63}$");

    /** Declared variable types; intentionally minimal until product defines more. */
    private static final Set<String> ALLOWED_TYPES =
        Set.of("STRING", "NUMBER", "BOOLEAN", "DATE");

    private TtsTemplateValidation() {
    }

    /**
     * Validates the declared variable schema.
     *
     * @return set of declared variable names (in declaration order), or
     *         {@code null} when the schema itself is absent/empty
     * @throws IllegalArgumentException with a caller-friendly message when
     *                                  the schema violates the contract
     */
    public static Set<String> validateSchema(List<TtsTemplateVariable> variables) {
        if (variables == null || variables.isEmpty()) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        for (TtsTemplateVariable variable : variables) {
            String name = variable.name() == null ? "" : variable.name().trim();
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                    "Variable name '" + name + "' is invalid. Use 1-64 characters: letters, digits, underscore.");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate variable declaration: " + name);
            }
            String type = variable.type();
            if (type != null && !ALLOWED_TYPES.contains(type.trim().toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                    "Variable '" + name + "' has unsupported type " + type
                        + ". Allowed: STRING, NUMBER, BOOLEAN, DATE.");
            }
        }
        return names;
    }

    /**
     * Validates template text against the declared schema: every
     * placeholder must be well-formed and declared; stray braces are
     * rejected so malformed templates never reach approval.
     *
     * @param declaredNames declared variable names, or null for none
     * @throws IllegalArgumentException on the first violation found
     */
    public static void validateTemplateText(String templateText, Set<String> declaredNames) {
        Matcher matcher = PLACEHOLDER.matcher(templateText == null ? "" : templateText);
        Set<String> referenced = new HashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                    "Malformed placeholder {{" + matcher.group(1) + "}}. Use simple names like {{firstName}}.");
            }
            if (declaredNames == null || !declaredNames.contains(name)) {
                throw new IllegalArgumentException(
                    "Template references undeclared variable {" + "{" + name + "}}. Declare it in the variable schema.");
            }
            referenced.add(name);
        }
        // Reject stray braces that placeholders did not consume.
        String withoutPlaceholders = matcher.reset(templateText == null ? "" : templateText).replaceAll("");
        if (STRAY_BRACE.matcher(withoutPlaceholders).find()) {
            throw new IllegalArgumentException(
                "Template text contains stray braces. Only {{variableName}} placeholders are allowed.");
        }
    }
}
